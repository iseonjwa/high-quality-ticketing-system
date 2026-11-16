package com.iseonjwa.reservation.seathold.concurrency;

import com.iseonjwa.reservation.queue.application.QueueService;
import com.iseonjwa.reservation.seathold.application.ConsumedEntryTokens;
import com.iseonjwa.reservation.seathold.application.HoldSeatsCommand;
import com.iseonjwa.reservation.seathold.application.SeatHoldExpiryService;
import com.iseonjwa.reservation.seathold.application.SyncSeatHoldService;
import com.iseonjwa.reservation.seathold.presentation.scheduling.SeatHoldExpiryScheduler;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 좌석 선점 부하 시나리오 L1·L2·L3·L6의 로컬 근사(docs/design/reservation/load-test-scenarios.md 3절)와,
 * 선점 구조 개선(ADR 0062)을 재는 L7·L8·L9(docs/design/reservation/seathold-structure-improvements.md 4절).
 *
 * <p>실제 Tomcat·Hikari·PostgreSQL에 HTTP로 부하를 준다. Gateway·인증·다른 기기의 부하 도구가 없고 부하 생성기와 대상이
 * 같은 JVM이라 SA 3-1의 공식 측정을 대신하지 않는다. 같은 장비에서 단계(S0~S4)와 커넥션 풀 크기의 전후를 비교하는 용도다.
 *
 * <p>Note: {@code SEATHOLD_LOAD=true}일 때만 돈다. 풀 크기는 {@code SEATHOLD_LOAD_POOL}(기본 5, config-repo와 같은 값)로 바꾼다.
 * 매수 한도가 측정을 가리지 않게 요청마다 새 사용자 ID를 쓴다.
 */
@Tag("load")
@EnabledIfEnvironmentVariable(named = "SEATHOLD_LOAD", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
                "spring.datasource.hikari.maximum-pool-size=${SEATHOLD_LOAD_POOL:5}",
                "spring.datasource.hikari.connection-timeout=${SEATHOLD_LOAD_CONNECTION_TIMEOUT:3000}",
                "seat-hold.max-concurrent=${SEATHOLD_LOAD_MAX_CONCURRENT:0}",
                "seat-hold.max-concurrent-queue=${SEATHOLD_LOAD_MAX_QUEUE:64}",
                "spring.threads.virtual.enabled=${SEATHOLD_LOAD_VIRTUAL_THREADS:false}",
                // 좌석 조회 1초 캐시로 이미 잡힌 좌석 선점을 트랜잭션 전에 거절(ISE-281). 켠 회와 끈 회를 비교한다
                "seat-hold.pre-reject.enabled=${SEATHOLD_LOAD_PRE_REJECT:true}"})
@ActiveProfiles("test")
class SeatHoldLoadTest {
    // Gateway 전체 타임아웃(SA 9-4)과 같게 둔다. 넘으면 타임아웃 오류로 센다
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration SAMPLE_INTERVAL = Duration.ofMillis(50);
    // 닫힌 모델 탐침 VU 수(L7·L8). SEATHOLD_LOAD_PROBE_RATE를 주면 대신 초당 그만큼 보내는 열린 모델 탐침을 쓴다(L9)
    private static final int PROBE_VUS = 20;
    // 시나리오 전후 차이를 보는 서버 지표. 아직 없는 지표(도입 전)는 건너뛴다
    private static final String CONNECTION_USAGE = "hikaricp.connections.usage";
    private static final String SEAT_LOCK = "seathold.seat_lock";
    private static final String LIMIT_WAIT = "seathold.concurrency.wait";
    private static final String LIMIT_IN_USE = "seathold.concurrency.in_use";
    private static final List<String> TRACKED_TIMERS = List.of(CONNECTION_USAGE, SEAT_LOCK, LIMIT_WAIT,
            "seathold.concurrency.held", "hikaricp.connections.acquire");
    // 탐침 대상. seats(기본)는 좌석 조회로, 1초 캐시라 커넥션을 회차당 초당 1번만 쓴다. requests는 접수 결과 조회에
    // 없는 요청 ID를 보내 읽기 트랜잭션 1번(커넥션 1번)을 쓰고 404를 받는다. 커넥션 보호를 보려면 requests를 쓴다
    private static final boolean PROBE_REQUESTS = "requests".equals(
            System.getenv().getOrDefault("SEATHOLD_LOAD_PROBE_TARGET", "seats"));
    // 소비자 재시도 모사(열린 모델). 프론트 seatHold.ts처럼 503·429·504·응답 없음은 같은 사용자·같은 멱등키로
    // 최대 4번 보낸다. 간격은 queryRetryDelay와 같다(1초 × 2^n, 최대 8초, +0~20%)
    private static final boolean CLIENT_RETRY = Boolean.parseBoolean(
            System.getenv().getOrDefault("SEATHOLD_LOAD_CLIENT_RETRY", "false"));
    private static final int CLIENT_MAX_ATTEMPTS = 4;
    // 배포 경로처럼 Gateway가 넣는 입장 토큰 ID(X-Entry-Token-Id)를 붙인다. 의도마다 새 jti, 재시도는 같은 jti다.
    // 붙이면 서버가 토큰 사용 여부를 한 번 더 읽고, 선점 뒤 대기열 Redis에서 그 토큰을 지운다
    private static final boolean ENTRY_TOKEN = Boolean.parseBoolean(
            System.getenv().getOrDefault("SEATHOLD_LOAD_ENTRY_TOKEN", "false"));
    // 대기열 정리(Redis 호출)에 더하는 지연. 배포의 Redis 왕복·지연을 흉내 낸다(로컬 Redis는 아주 빠르다)
    private static final long DISCARD_DELAY_MICROS = Long.parseLong(
            System.getenv().getOrDefault("SEATHOLD_LOAD_DISCARD_DELAY_US", "0"));

    @TestConfiguration
    static class DelayedEntryTokenCleanup {
        @Bean
        @Primary
        ConsumedEntryTokens delayedConsumedEntryTokens(QueueService queue) {
            return new ConsumedEntryTokens(queue) {
                @Override
                public void discard(long scheduleId, long userId, String entryTokenJti) {
                    if (DISCARD_DELAY_MICROS > 0 && entryTokenJti != null) {
                        java.util.concurrent.locks.LockSupport.parkNanos(DISCARD_DELAY_MICROS * 1_000);
                    }
                    super.discard(scheduleId, userId, entryTokenJti);
                }
            };
        }
    }
    private static final int PROBE_RATE = Integer.parseInt(
            System.getenv().getOrDefault("SEATHOLD_LOAD_PROBE_RATE", "0"));
    // 선점 대상 회차의 좌석 조회를 초당 이만큼 보낸다(열린 모델). 사용자가 좌석을 보고 고르는 흐름을 흉내 내고, 사전 거절이 읽는
    // 1초 캐시를 채운다. 0이면 보내지 않아 캐시가 비고 사전 거절도 일어나지 않는다(ISE-281)
    // true면 선점 대신 자동 배정 API(구역 A, 매수 = 좌석 고르기가 고른 좌석 수)를 보낸다(ISE-264·ISE-287 기준값).
    // 재시도 모사·의도 지표·정합성 검사는 그대로 쓴다. 자동 배정은 잠긴 좌석을 건너뛰므로(SKIP LOCKED) 잠금 장애 없이 잰다
    private static final boolean AUTO_ASSIGN = Boolean.parseBoolean(
            System.getenv().getOrDefault("SEATHOLD_LOAD_AUTO", "false"));
    private static final int SEAT_VIEW_RATE = Integer.parseInt(
            System.getenv().getOrDefault("SEATHOLD_LOAD_SEAT_VIEW_RATE", "0"));
    // 503을 받은 사용자가 다시 누르기까지의 시간. 0이면 바로 다시 보낸다. 쉬지 않으면 503 폭주가 같은 JVM의 CPU를 먹어
    // 서버 처리량까지 낮추므로, 동시 처리 상한을 켠 측정은 이 값을 둔다(ADR 0062)
    private static final Duration OVERLOAD_BACKOFF = Duration.ofMillis(
            Long.parseLong(System.getenv().getOrDefault("SEATHOLD_LOAD_503_BACKOFF_MS", "0")));

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DataSource dataSource;
    @Autowired
    MeterRegistry meterRegistry;
    @Autowired
    SyncSeatHoldService syncService;
    @Autowired
    SeatHoldExpiryService expiryService;
    @Value("${spring.datasource.url}")
    String dbUrl;
    @Value("${spring.datasource.username}")
    String dbUser;
    @Value("${spring.datasource.password}")
    String dbPassword;

    private final AtomicLong userSequence = new AtomicLong(800_000_000L);
    // 사용자가 201로 받은 좌석 수. 잡힌 좌석 수에서 빼면 타임아웃 뒤 서버가 잡은 좌석(유령 선점)이 나온다
    private final AtomicInteger acknowledgedSeats = new AtomicInteger();
    private HttpClient http;
    private SeatHoldFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        http = HttpClient.newBuilder().executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(1)).build();
        fixture = new SeatHoldFixture(jdbc);
        warmUp();
    }

    @AfterEach
    void cleanUp() {
        http.close();
        fixture.cleanUp();
    }

    // L1(SA 3장 정의): VU 100이 1초 안에 시작해 각 10회. 좌석 15,000석에서 서로 다른 무작위 좌석 1~2개
    @Test
    void L1_기준선() throws Exception {
        // VU마다 보내는 횟수. 기본 10은 SA 3장 정의다. 성공 경로를 길게 재려면 SEATHOLD_LOAD_L1_ITERATIONS로 늘린다
        var iterations = Integer.parseInt(System.getenv().getOrDefault("SEATHOLD_LOAD_L1_ITERATIONS", "10"));
        for (var round = 0; round < repeat(); round++) {
            var scheduleId = fixture.createOnSaleSchedule();
            var seats = fixture.createSeatsInBulk(scheduleId, 15_000);
            var result = run(scheduleId, 100, Duration.ofSeconds(1), iterations, null, randomSeats(seats));
            report("L1", result, scheduleId, Map.of("vus", 100, "iterations", iterations, "round", round + 1));
        }
    }

    // L2(Synthetic 쏠림): VU 200, 30초. 요청의 80%가 좌석 1,000석 중 앞 5%(50석)에 몰린다
    @Test
    void L2_인기_좌석_쏠림() throws Exception {
        var scheduleId = fixture.createOnSaleSchedule();
        var seats = fixture.createSeatsInBulk(scheduleId, 1_000);
        var hot = seats.subList(0, 50);
        Function<ThreadLocalRandom, List<Long>> chooser = random -> List.of(random.nextInt(100) < 80
                ? hot.get(random.nextInt(hot.size())) : seats.get(random.nextInt(seats.size())));
        var result = run(scheduleId, 200, Duration.ofSeconds(1), 0, Duration.ofSeconds(30), chooser);
        report("L2", result, scheduleId, Map.of("vus", 200));
    }

    // L3(포화점 계단): VU 50 → 100 → 200 → 400 → 800, 단계마다 20초. 단계마다 새 회차 15,000석
    // 반복 측정용으로 단계와 길이를 SEATHOLD_LOAD_STEPS(예: 100,400)·SEATHOLD_LOAD_STEP_SECONDS로 줄일 수 있다
    @Test
    void L3_포화점_계단() throws Exception {
        var steps = System.getenv().getOrDefault("SEATHOLD_LOAD_STEPS", "50,100,200,400,800");
        var stepSeconds = Integer.parseInt(System.getenv().getOrDefault("SEATHOLD_LOAD_STEP_SECONDS", "20"));
        for (var step : steps.split(",")) {
            var vus = Integer.parseInt(step.trim());
            var scheduleId = fixture.createOnSaleSchedule();
            var seats = fixture.createSeatsInBulk(scheduleId, 15_000);
            var result = run(scheduleId, vus, Duration.ofSeconds(1), 0, Duration.ofSeconds(stepSeconds),
                    randomSeats(seats));
            report("L3", result, scheduleId, Map.of("vus", vus));
        }
    }

    // L6(E1 확대): VU 1,000이 1초 안에 시작해 각 1회. 좌석 10개 중 하나를 고른다. 좌석당 성공 1, 나머지 409여야 한다
    @Test
    void L6_핫_좌석_집중() throws Exception {
        var scheduleId = fixture.createOnSaleSchedule();
        var seats = fixture.createSeatsInBulk(scheduleId, 10);
        var result = run(scheduleId, 1_000, Duration.ofSeconds(1), 1, null,
                random -> List.of(seats.get(random.nextInt(seats.size()))));
        report("L6", result, scheduleId, Map.of("vus", 1_000));
        assertEquals(10L, result.countByStatus().getOrDefault("201", 0L));
    }

    // L7(여러 좌석 쏠림, 선점 구조 개선 측정): 좌석 1,000석. 요청의 80%가 앞 100석(인기 구역)에서 이웃한 좌석 1~4개를 고른다.
    // 인기 구역이 금방 잡혀, 이미 잡힌 좌석이 섞인 여러 좌석 요청이 대부분인 오픈 직후를 흉내 낸다.
    // 좌석 조회 탐침(다른 회차)을 함께 돌려 선점 부하가 다른 API로 번지는지 본다. 반복은 SEATHOLD_LOAD_REPEAT로 정한다
    @Test
    void L7_여러_좌석_쏠림() throws Exception {
        var vus = Integer.parseInt(System.getenv().getOrDefault("SEATHOLD_LOAD_L7_VUS", "200"));
        for (var round = 0; round < repeat(); round++) {
            var scheduleId = fixture.createOnSaleSchedule();
            var seats = fixture.createSeatsInBulk(scheduleId, 1_000);
            var probeScheduleId = probeSchedule();
            var result = run(scheduleId, vus, Duration.ofSeconds(1), 0, Duration.ofSeconds(20),
                    neighborSeats(seats, 100), probeScheduleId, null, null);
            report("L7", result, scheduleId, Map.of("vus", vus, "round", round + 1));
        }
    }

    // L8(잠금 장애 중 번짐): L7과 같은 요청에 VU를 늘리고, 인기 구역 20석을 측정 내내 잠근 느린 트랜잭션을 둔다(측정 브랜치 Z와 같은 상황).
    // 잠긴 좌석을 노린 요청이 lock_timeout까지 커넥션을 쥐므로, 상관없는 좌석 조회가 얼마나 버티는지가 핵심 지표다
    @Test
    void L8_잠금_장애_번짐() throws Exception {
        var vus = Integer.parseInt(System.getenv().getOrDefault("SEATHOLD_LOAD_L8_VUS", "400"));
        for (var round = 0; round < repeat(); round++) {
            var scheduleId = fixture.createOnSaleSchedule();
            var seats = fixture.createSeatsInBulk(scheduleId, 1_000);
            var probeScheduleId = probeSchedule();
            var result = run(scheduleId, vus, Duration.ofSeconds(1), 0, Duration.ofSeconds(20),
                    neighborSeats(seats, 100), probeScheduleId, seats.subList(0, 20), null);
            report("L8", result, scheduleId, Map.of("vus", vus, "round", round + 1));
        }
    }

    // L9(열린 모델, 동시 처리 상한 측정): L8과 같은 좌석·잠금 장애에서 VU 대신 초당 도착 수를 고정한다(k6 constant-arrival-rate).
    // 닫힌 모델은 503을 받은 VU가 바로 다시 보내거나(폭주) 쉬어서(부하 감소) 상한 전후의 부하가 같지 않다.
    // 도착률은 SEATHOLD_LOAD_RATES(기본 300,600)로 정한다. 300은 처리 가능한 양 아래, 600은 위다
    @Test
    void L9_일정_도착률_잠금_장애() throws Exception {
        var rates = System.getenv().getOrDefault("SEATHOLD_LOAD_RATES", "300,600");
        for (var rate : rates.split(",")) {
            for (var round = 0; round < repeat(); round++) {
                var scheduleId = fixture.createOnSaleSchedule();
                var seats = fixture.createSeatsInBulk(scheduleId, 1_000);
                var probeScheduleId = probeSchedule();
                var arrivalRate = Integer.parseInt(rate.trim());
                // SEATHOLD_LOAD_L9_LOCKED=false면 잠금 장애 없이 같은 도착률만 준다(정상 오픈 피크에서 상한의 503을 본다)
                var locked = Boolean.parseBoolean(System.getenv().getOrDefault("SEATHOLD_LOAD_L9_LOCKED", "true"));
                var result = run(scheduleId, 0, Duration.ZERO, 0, Duration.ofSeconds(20), neighborSeats(seats, 100),
                        probeScheduleId, locked ? seats.subList(0, 20) : null, arrivalRate);
                report("L9", result, scheduleId, Map.of("rate", arrivalRate, "round", round + 1, "locked", locked));
            }
        }
    }

    // L10(만료 회수): 결제하지 않은 선점 N건의 기한을 지나게 한 뒤, 만료 스케줄러를 운영과 같은 fixedDelay로 돌려 모두 회수하는
    // 시간을 잰다. 회수하는 동안 다른 회차에 L7과 같은 선점 부하를 줘서, 만료 주기가 선점과 좌석 조회에 주는 영향도 본다.
    // 주기는 SEATHOLD_LOAD_EXPIRY_INTERVALS(밀리초, 기본 5000,1000), 건수는 SEATHOLD_LOAD_EXPIRY_TARGETS(기본 3000)로 정한다
    @Test
    void L10_만료_회수() throws Exception {
        var intervals = System.getenv().getOrDefault("SEATHOLD_LOAD_EXPIRY_INTERVALS", "5000,1000");
        var targets = Integer.parseInt(System.getenv().getOrDefault("SEATHOLD_LOAD_EXPIRY_TARGETS", "3000"));
        for (var intervalText : intervals.split(",")) {
            for (var round = 0; round < repeat(); round++) {
                var interval = Duration.ofMillis(Long.parseLong(intervalText.trim()));
                var expiringScheduleId = fixture.createOnSaleSchedule();
                var expiringSeats = fixture.createSeatsInBulk(expiringScheduleId, targets);
                holdAll(expiringScheduleId, expiringSeats);
                fixture.passHoldExpiry(expiringScheduleId);

                var loadScheduleId = fixture.createOnSaleSchedule();
                var loadSeats = fixture.createSeatsInBulk(loadScheduleId, 1_000);
                var drainSeconds = new AtomicReference<Double>();
                var scheduler = new SeatHoldExpiryScheduler(expiryService, 100);
                var timer = Executors.newSingleThreadScheduledExecutor();
                var started = System.nanoTime();
                timer.scheduleWithFixedDelay(scheduler::expireDueHolds, interval.toMillis(), interval.toMillis(),
                        TimeUnit.MILLISECONDS);
                var watcher = Thread.ofPlatform().start(() -> {
                    while (heldReservations(expiringScheduleId) > 0
                            && System.nanoTime() - started < TimeUnit.SECONDS.toNanos(400)) {
                        sleepQuietly(Duration.ofMillis(100));
                    }
                    drainSeconds.set((System.nanoTime() - started) / 1e9);
                });
                var result = run(loadScheduleId, 50, Duration.ofSeconds(1), 0, Duration.ofSeconds(20),
                        neighborSeats(loadSeats, 100), probeSchedule(), null, null);
                watcher.join();
                timer.shutdownNow();
                var left = heldReservations(expiringScheduleId);
                report("L10", result, loadScheduleId, Map.of("intervalMs", interval.toMillis(), "targets", targets,
                        "round", round + 1, "drainSeconds", Math.round(drainSeconds.get() * 10) / 10.0,
                        "releasedPerSecond", Math.round((targets - left) / drainSeconds.get()), "left", left));
            }
        }
    }

    // 서로 다른 사용자가 1석씩 잡는다. 회수 측정 전에 만드는 데이터라 서비스를 직접 부른다
    private void holdAll(Long scheduleId, List<Long> seats) {
        try (var executor = Executors.newFixedThreadPool(4)) {
            for (var seat : seats) {
                var userId = userSequence.incrementAndGet();
                executor.submit(() -> syncService.hold(new HoldSeatsCommand(userId, UUID.randomUUID(), scheduleId,
                        List.of(seat), null)));
            }
        }
    }

    private int heldReservations(Long scheduleId) {
        return jdbc.queryForObject("SELECT count(*) FROM reservations WHERE schedule_id = ? AND status = 'HELD'",
                Integer.class, scheduleId);
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private long preRejected() {
        var counter = meterRegistry.find("seathold.pre_rejected").counter();
        return counter == null ? 0 : (long) counter.count();
    }

    // 측정 하나 동안의 선점 대상 회차 좌석 조회와 사전 거절 수. 결과 기록 형식(RunResult)을 바꾸지 않으려고 필드에 둔다
    private final ConcurrentLinkedQueue<Sample> seatViews = new ConcurrentLinkedQueue<>();
    private long preRejectedInRun;

    private long rejected(String reason) {
        var counter = meterRegistry.find("seathold.concurrency.rejected").tag("reason", reason).counter();
        return counter == null ? 0 : (long) counter.count();
    }

    // 타이머 누적값(건수, 합계, 버킷별 건수)을 시나리오 시작 때 적어 둔다
    private Map<String, double[]> timerMarks() {
        var marks = new LinkedHashMap<String, double[]>();
        for (var name : TRACKED_TIMERS) {
            var timer = meterRegistry.find(name).timer();
            if (timer != null) {
                marks.put(name, mark(timer));
            }
        }
        return marks;
    }

    private static double[] mark(Timer timer) {
        var buckets = timer.takeSnapshot().histogramCounts();
        var values = new double[2 + buckets.length];
        values[0] = timer.count();
        values[1] = timer.totalTime(TimeUnit.MICROSECONDS);
        for (var i = 0; i < buckets.length; i++) {
            values[2 + i] = buckets[i].count();
        }
        return values;
    }

    // 시나리오 동안의 건수, 평균, 버킷 이하 비율(누적)을 낸다
    private Map<String, Object> timerDeltas(Map<String, double[]> before) {
        var deltas = new LinkedHashMap<String, Object>();
        for (var name : TRACKED_TIMERS) {
            var timer = meterRegistry.find(name).timer();
            if (timer == null) {
                continue;
            }
            var end = mark(timer);
            var start = before.getOrDefault(name, new double[end.length]);
            var count = end[0] - start[0];
            var summary = new LinkedHashMap<String, Object>();
            summary.put("count", (long) count);
            summary.put("avgUs", count == 0 ? 0 : Math.round((end[1] - start[1]) / count));
            var buckets = timer.takeSnapshot().histogramCounts();
            for (var i = 0; i < buckets.length; i++) {
                var share = count == 0 ? 0 : Math.round(1000.0 * (end[2 + i] - start[2 + i]) / count) / 10.0;
                summary.put("le" + (long) buckets[i].bucket(TimeUnit.MILLISECONDS) + "ms%", share);
            }
            deltas.put(name, summary);
        }
        return deltas;
    }

    private static int repeat() {
        return Integer.parseInt(System.getenv().getOrDefault("SEATHOLD_LOAD_REPEAT", "1"));
    }

    private Long probeSchedule() {
        var probeScheduleId = fixture.createOnSaleSchedule();
        fixture.createSeatsInBulk(probeScheduleId, 1_000);
        return probeScheduleId;
    }

    // 1석 30%, 2석 50%, 3석·4석 각 10%. 사용자는 붙은 좌석을 고르므로 시작 좌석부터 ID가 이어진 좌석을 잡는다
    private static Function<ThreadLocalRandom, List<Long>> neighborSeats(List<Long> seats, int hotCount) {
        return random -> {
            var roll = random.nextInt(100);
            var count = roll < 30 ? 1 : roll < 80 ? 2 : roll < 90 ? 3 : 4;
            var range = random.nextInt(100) < 80 ? hotCount : seats.size();
            var start = random.nextInt(range - count + 1);
            return seats.subList(start, start + count);
        };
    }

    private static Function<ThreadLocalRandom, List<Long>> randomSeats(List<Long> seats) {
        return random -> {
            var first = random.nextInt(seats.size());
            if (random.nextBoolean()) {
                return List.of(seats.get(first));
            }
            var second = (first + 1 + random.nextInt(seats.size() - 1)) % seats.size();
            return List.of(seats.get(first), seats.get(second));
        };
    }

    // JIT·커넥션·쿼리 계획 준비가 첫 수치를 왜곡하지 않게 본 측정 전에 낮은 부하를 준다(load-test-scenarios.md 2절 4번)
    private void warmUp() throws Exception {
        var scheduleId = fixture.createOnSaleSchedule();
        var seats = fixture.createSeatsInBulk(scheduleId, 1_000);
        run(scheduleId, 10, Duration.ZERO, 0, Duration.ofSeconds(5), randomSeats(seats));
    }

    record Sample(String status, long startNanos, long endNanos) {
        long micros() {
            return TimeUnit.NANOSECONDS.toMicros(endNanos - startNanos);
        }
    }

    // 사용자 한 명의 선점 시도 전체(재시도 포함). status는 마지막 응답, attempts는 보낸 횟수
    record Intent(String status, long startNanos, long endNanos, int attempts) {
        long micros() {
            return TimeUnit.NANOSECONDS.toMicros(endNanos - startNanos);
        }
    }

    record RunResult(List<Sample> samples, int maxPendingConnections, int maxLockWaiters, List<Sample> probes,
            int maxLimitInUse, Map<String, Object> meters, List<Intent> intents, long rejectedQueueFull,
            long rejectedWaitTimeout) {
        Map<String, Long> countByStatus() {
            var counts = new TreeMap<String, Long>();
            for (var sample : samples) {
                counts.merge(sample.status(), 1L, Long::sum);
            }
            return counts;
        }
    }

    /**
     * 닫힌 모델로 부하를 준다. VU마다 가상 스레드 하나가 응답을 받은 뒤 다음 요청을 보낸다.
     * iterations가 0보다 크면 VU마다 그 횟수만큼, 아니면 duration 동안 보낸다. VU 시작은 ramp 동안 고르게 흩는다.
     */
    private RunResult run(Long scheduleId, int vus, Duration ramp, int iterations, Duration duration,
            Function<ThreadLocalRandom, List<Long>> chooser) throws Exception {
        return run(scheduleId, vus, ramp, iterations, duration, chooser, null, null, null);
    }

    /**
     * probeScheduleId가 있으면 탐침 VU 20개가 측정 동안 그 회차의 좌석 조회를 반복한다(선점과 상관없는 API).
     * lockedSeats가 있으면 측정 동안 그 좌석들을 FOR UPDATE로 쥔 트랜잭션을 앱 풀 밖의 연결로 둔다.
     * arrivalRate가 있으면 열린 모델이다. VU 수와 상관없이 초당 그만큼 새 요청을 보내고, 응답이 늦어도 다음 요청을 미루지 않는다.
     */
    private RunResult run(Long scheduleId, int vus, Duration ramp, int iterations, Duration duration,
            Function<ThreadLocalRandom, List<Long>> chooser, Long probeScheduleId, List<Long> lockedSeats,
            Integer arrivalRate) throws Exception {
        acknowledgedSeats.set(0);
        var samples = new ConcurrentLinkedQueue<Sample>();
        var probes = new ConcurrentLinkedQueue<Sample>();
        var intents = new ConcurrentLinkedQueue<Intent>();
        var running = new AtomicBoolean(true);
        var maxPending = new AtomicInteger();
        var maxLockWaiters = new AtomicInteger();
        var maxLimitInUse = new AtomicInteger();
        var before = timerMarks();
        var queueFullBefore = rejected("queue_full");
        var waitTimeoutBefore = rejected("wait_timeout");
        var locker = lockedSeats == null ? null : startLocker(lockedSeats, running);
        seatViews.clear();
        var preRejectedBefore = preRejected();
        var sampler = Thread.ofPlatform().start(() -> sample(running, maxPending, maxLockWaiters, maxLimitInUse));
        var probeExecutor = Executors.newVirtualThreadPerTaskExecutor();
        if (probeScheduleId != null && PROBE_RATE > 0) {
            // 열린 모델 탐침: 초당 PROBE_RATE건을 응답과 상관없이 보낸다. 탐침이 DB 자리를 빼앗아 선점 측정을 흔들지 않는다
            probeExecutor.submit(() -> {
                var start = System.nanoTime();
                for (var i = 0L; running.get(); i++) {
                    var wait = start + i * 1_000_000_000L / PROBE_RATE - System.nanoTime();
                    if (wait > 0) {
                        TimeUnit.NANOSECONDS.sleep(wait);
                    }
                    probeExecutor.submit(() -> probes.add(probe(probeScheduleId)));
                }
                return null;
            });
        }
        if (SEAT_VIEW_RATE > 0) {
            probeExecutor.submit(() -> {
                var start = System.nanoTime();
                for (var i = 0L; running.get(); i++) {
                    var wait = start + i * 1_000_000_000L / SEAT_VIEW_RATE - System.nanoTime();
                    if (wait > 0) {
                        TimeUnit.NANOSECONDS.sleep(wait);
                    }
                    probeExecutor.submit(() -> seatViews.add(get("/api/v1/schedules/" + scheduleId + "/seats", false)));
                }
                return null;
            });
        }
        if (probeScheduleId != null && PROBE_RATE <= 0) {
            for (var probe = 0; probe < PROBE_VUS; probe++) {
                probeExecutor.submit(() -> {
                    while (running.get()) {
                        probes.add(probe(probeScheduleId));
                    }
                    return null;
                });
            }
        }
        var deadline = duration == null ? Long.MAX_VALUE : System.nanoTime() + ramp.toNanos() + duration.toNanos();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            if (arrivalRate != null) {
                var start = System.nanoTime();
                var total = arrivalRate * duration.toSeconds();
                for (var i = 0L; i < total; i++) {
                    var wait = start + i * 1_000_000_000L / arrivalRate - System.nanoTime();
                    if (wait > 0) {
                        TimeUnit.NANOSECONDS.sleep(wait);
                    }
                    if (CLIENT_RETRY) {
                        executor.submit(() -> intents.add(intent(scheduleId, chooser.apply(ThreadLocalRandom.current()),
                                samples)));
                    } else {
                        executor.submit(() -> samples.add(send(scheduleId,
                                chooser.apply(ThreadLocalRandom.current()))));
                    }
                }
            }
            var closedVus = arrivalRate == null ? vus : 0;
            for (var vu = 0; vu < closedVus; vu++) {
                var delayNanos = closedVus == 1 ? 0 : ramp.toNanos() * vu / (closedVus - 1);
                executor.submit(() -> {
                    TimeUnit.NANOSECONDS.sleep(delayNanos);
                    var random = ThreadLocalRandom.current();
                    for (var i = 0; iterations > 0 ? i < iterations : System.nanoTime() < deadline; i++) {
                        var sample = send(scheduleId, chooser.apply(random));
                        samples.add(sample);
                        if (sample.status().equals("503") && OVERLOAD_BACKOFF.isPositive()) {
                            Thread.sleep(OVERLOAD_BACKOFF);
                        }
                    }
                    return null;
                });
            }
        } finally {
            running.set(false);
            sampler.join();
            probeExecutor.close();
            preRejectedInRun = preRejected() - preRejectedBefore;
            if (locker != null) {
                locker.join();
            }
        }
        return new RunResult(new ArrayList<>(samples), maxPending.get(), maxLockWaiters.get(),
                new ArrayList<>(probes), maxLimitInUse.get(), timerDeltas(before), new ArrayList<>(intents),
                rejected("queue_full") - queueFullBefore, rejected("wait_timeout") - waitTimeoutBefore);
    }

    // 앱 풀 밖의 연결로 좌석을 잠근다. 측정이 끝나면 롤백해 좌석을 그대로 둔다. 잠금을 잡은 뒤에 부하를 시작한다
    private Thread startLocker(List<Long> lockedSeats, AtomicBoolean running) throws InterruptedException {
        var locked = new CountDownLatch(1);
        var thread = Thread.ofPlatform().start(() -> {
            try (var connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
                connection.setAutoCommit(false);
                try (var statement = connection.prepareStatement(
                        "SELECT id FROM schedule_seats WHERE id = ANY (?) ORDER BY id FOR UPDATE")) {
                    statement.setArray(1, connection.createArrayOf("bigint", lockedSeats.toArray()));
                    statement.executeQuery().close();
                }
                locked.countDown();
                while (running.get()) {
                    Thread.sleep(SAMPLE_INTERVAL);
                }
                connection.rollback();
            } catch (Exception exception) {
                throw new IllegalStateException("좌석 잠금 트랜잭션을 유지하지 못했습니다", exception);
            }
        });
        if (!locked.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("좌석 잠금을 잡지 못했습니다");
        }
        return thread;
    }

    private Sample probe(Long scheduleId) {
        var path = PROBE_REQUESTS ? "/api/v1/reservations/requests/" + UUID.randomUUID()
                : "/api/v1/schedules/" + scheduleId + "/seats";
        return get(path, PROBE_REQUESTS);
    }

    // notFoundIsOk: 접수 결과 조회 탐침은 없는 요청 ID라 404가 정상 응답이다
    private Sample get(String path, boolean notFoundIsOk) {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(REQUEST_TIMEOUT)
                .header("X-User-Id", "1")
                .header("X-User-Role", "USER")
                .GET()
                .build();
        var start = System.nanoTime();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.discarding());
            var status = notFoundIsOk && response.statusCode() == 404 ? 200 : response.statusCode();
            return new Sample(String.valueOf(status), start, System.nanoTime());
        } catch (HttpTimeoutException timeout) {
            return new Sample("TIMEOUT", start, System.nanoTime());
        } catch (Exception exception) {
            return new Sample("CLIENT_ERROR:" + exception.getClass().getSimpleName(), start, System.nanoTime());
        }
    }

    // 같은 사용자·같은 멱등키로 재시도한다. 서버는 같은 키를 재응답하므로 앞 시도가 타임아웃 뒤 처리됐어도 좌석이 두 번 잡히지 않는다
    private Intent intent(Long scheduleId, List<Long> seatIds, ConcurrentLinkedQueue<Sample> samples)
            throws InterruptedException {
        var userId = userSequence.incrementAndGet();
        var key = UUID.randomUUID().toString();
        var jti = ENTRY_TOKEN ? UUID.randomUUID().toString() : null;
        var start = System.nanoTime();
        Sample last = null;
        var attempts = 0;
        while (attempts < CLIENT_MAX_ATTEMPTS) {
            last = send(scheduleId, seatIds, userId, key, jti);
            samples.add(last);
            attempts++;
            if (!clientRetryable(last.status()) || attempts >= CLIENT_MAX_ATTEMPTS) {
                break;
            }
            var baseMillis = Math.min(1_000L << (attempts - 1), 8_000L);
            Thread.sleep(Math.round(baseMillis * (1 + ThreadLocalRandom.current().nextDouble() * 0.2)));
        }
        return new Intent(last.status(), start, System.nanoTime(), attempts);
    }

    private static boolean clientRetryable(String status) {
        return status.equals("503") || status.equals("429") || status.equals("504") || status.equals("TIMEOUT")
                || status.startsWith("CLIENT_ERROR");
    }

    private Sample send(Long scheduleId, List<Long> seatIds) {
        return send(scheduleId, seatIds, userSequence.incrementAndGet(), UUID.randomUUID().toString(),
                ENTRY_TOKEN ? UUID.randomUUID().toString() : null);
    }

    private Sample send(Long scheduleId, List<Long> seatIds, long userId, String idempotencyKey, String jti) {
        var body = AUTO_ASSIGN ? "{\"section\":\"A\",\"quantity\":" + seatIds.size() + "}"
                : "{\"seatIds\":" + seatIds + "}";
        var path = "/api/v1/schedules/" + scheduleId + (AUTO_ASSIGN ? "/reservations/auto" : "/reservations");
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("X-User-Id", String.valueOf(userId))
                .header("X-User-Role", "USER")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (jti != null) {
            builder.header("X-Entry-Token-Id", jti);
        }
        var request = builder.build();
        var start = System.nanoTime();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            var status = String.valueOf(response.statusCode());
            if (response.statusCode() == 409) {
                status = "409:" + errorCode(response.body());
            }
            if (response.statusCode() == 201) {
                acknowledgedSeats.addAndGet(seatIds.size());
            }
            return new Sample(status, start, System.nanoTime());
        } catch (HttpTimeoutException timeout) {
            return new Sample("TIMEOUT", start, System.nanoTime());
        } catch (Exception exception) {
            return new Sample("CLIENT_ERROR:" + exception.getClass().getSimpleName(), start, System.nanoTime());
        }
    }

    private static String errorCode(String body) {
        var marker = "\"code\":\"";
        var index = body.indexOf(marker);
        if (index < 0) {
            return "UNKNOWN";
        }
        var start = index + marker.length();
        return body.substring(start, body.indexOf('"', start));
    }

    // Note: 잠금 대기 세션 수는 앱 커넥션 풀과 별도의 연결로 잰다. 같은 풀을 쓰면 측정이 풀 자리를 뺏어 결과를 바꾼다
    private void sample(AtomicBoolean running, AtomicInteger maxPending, AtomicInteger maxLockWaiters,
            AtomicInteger maxLimitInUse) {
        try (var connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
                var statement = connection.prepareStatement("SELECT count(*) FROM pg_stat_activity"
                        + " WHERE datname = current_database() AND wait_event_type = 'Lock'")) {
            var pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
            while (running.get()) {
                maxPending.accumulateAndGet(pool.getThreadsAwaitingConnection(), Math::max);
                var inUse = meterRegistry.find(LIMIT_IN_USE).gauge();
                if (inUse != null) {
                    maxLimitInUse.accumulateAndGet((int) inUse.value(), Math::max);
                }
                try (var resultSet = statement.executeQuery()) {
                    resultSet.next();
                    maxLockWaiters.accumulateAndGet(resultSet.getInt(1), Math::max);
                }
                Thread.sleep(SAMPLE_INTERVAL);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("부하 지표를 수집하지 못했습니다", exception);
        }
    }

    private void report(String scenario, RunResult result, Long scheduleId, Map<String, ?> extra) throws Exception {
        var samples = result.samples();
        var micros = samples.stream().mapToLong(Sample::micros).sorted().toArray();
        var firstStart = samples.stream().mapToLong(Sample::startNanos).min().orElse(0);
        var lastEnd = samples.stream().mapToLong(Sample::endNanos).max().orElse(0);
        var seconds = Math.max(1e-9, (lastEnd - firstStart) / 1e9);
        var created = samples.stream().filter(sample -> sample.status().equals("201")).mapToLong(Sample::micros)
                .sorted().toArray();
        var rejected = samples.stream().filter(sample -> sample.status().startsWith("409")).mapToLong(Sample::micros)
                .sorted().toArray();
        var metrics = new LinkedHashMap<String, Object>(extra);
        metrics.put("pool", dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize());
        metrics.put("requests", samples.size());
        metrics.put("throughput", Math.round(samples.size() / seconds));
        metrics.put("avgMs", micros.length == 0 ? 0 : Math.round(average(micros) / 1000.0));
        metrics.put("p50Ms", percentileMs(micros, 50));
        metrics.put("p95Ms", percentileMs(micros, 95));
        metrics.put("p99Ms", percentileMs(micros, 99));
        metrics.put("p95Ms201", percentileMs(created, 95));
        metrics.put("p95Ms409", percentileMs(rejected, 95));
        metrics.put("status", result.countByStatus());
        metrics.put("maxPendingConnections", result.maxPendingConnections());
        metrics.put("maxLockWaiters", result.maxLockWaiters());
        var seatsHeld = jdbc.queryForObject("SELECT count(*) FROM schedule_seats WHERE schedule_id = ?"
                + " AND status = 'HELD'", Integer.class, scheduleId);
        metrics.put("seatsHeld", seatsHeld);
        metrics.put("ghostSeats", seatsHeld - acknowledgedSeats.get());
        if (!result.probes().isEmpty()) {
            var probes = result.probes();
            var probeMicros = probes.stream().mapToLong(Sample::micros).sorted().toArray();
            var probeCounts = new TreeMap<String, Long>();
            probes.forEach(probe -> probeCounts.merge(probe.status(), 1L, Long::sum));
            metrics.put("probeRequests", probes.size());
            metrics.put("probeOkRate", Math.round(1000.0 * probeCounts.getOrDefault("200", 0L) / probes.size()) / 10.0);
            metrics.put("probeP50Ms", percentileMs(probeMicros, 50));
            metrics.put("probeP95Ms", percentileMs(probeMicros, 95));
            metrics.put("probeStatus", probeCounts);
        }
        if (!result.intents().isEmpty()) {
            // 소비자 기준: 사용자 한 명이 재시도까지 해서 받은 마지막 결과와 걸린 시간
            var intents = result.intents();
            var intentCounts = new TreeMap<String, Long>();
            intents.forEach(intent -> intentCounts.merge(intent.status(), 1L, Long::sum));
            var finished = intentCounts.getOrDefault("201", 0L) + intents.stream()
                    .filter(intent -> intent.status().startsWith("409")).count();
            var intentMicros = intents.stream().mapToLong(Intent::micros).sorted().toArray();
            metrics.put("intents", intents.size());
            metrics.put("intentFailRate", Math.round(1000.0 * (intents.size() - finished) / intents.size()) / 10.0);
            metrics.put("intentAvgAttempts",
                    Math.round(100.0 * intents.stream().mapToInt(Intent::attempts).average().orElse(0)) / 100.0);
            metrics.put("intentP50Ms", percentileMs(intentMicros, 50));
            metrics.put("intentP95Ms", percentileMs(intentMicros, 95));
            metrics.put("intentStatus", intentCounts);
        }
        if (!seatViews.isEmpty()) {
            var views = new ArrayList<>(seatViews);
            var viewMicros = views.stream().mapToLong(Sample::micros).sorted().toArray();
            var viewOk = views.stream().filter(view -> view.status().equals("200")).count();
            metrics.put("seatViews", views.size());
            metrics.put("seatViewOkRate", Math.round(1000.0 * viewOk / views.size()) / 10.0);
            metrics.put("seatViewP95Ms", percentileMs(viewMicros, 95));
        }
        // 사전 거절(캐시) 수와 409 중 비율. 끈 회는 0이다
        var conflicts = samples.stream().filter(sample -> sample.status().startsWith("409")).count();
        metrics.put("preRejected", preRejectedInRun);
        metrics.put("preRejectedOf409", conflicts == 0 ? 0 : Math.round(1000.0 * preRejectedInRun / conflicts) / 10.0);
        metrics.put("maxLimitInUse", result.maxLimitInUse());
        metrics.put("rejectedQueueFull", result.rejectedQueueFull());
        metrics.put("rejectedWaitTimeout", result.rejectedWaitTimeout());
        metrics.putAll(result.meters());
        metrics.put("doubleSold", fixture.doubleSoldSeats(scheduleId));
        metrics.put("seatMismatches", fixture.seatStatusMismatches(scheduleId));
        System.out.printf("[SEATHOLD-LOAD] %s %s%n", scenario, metrics);
    }

    private static double average(long[] values) {
        var sum = 0L;
        for (var value : values) {
            sum += value;
        }
        return (double) sum / values.length;
    }

    private static long percentileMs(long[] sortedMicros, double percentile) {
        if (sortedMicros.length == 0) {
            return 0;
        }
        var index = (int) Math.ceil(percentile / 100.0 * sortedMicros.length) - 1;
        return Math.round(sortedMicros[Math.max(0, index)] / 1000.0);
    }
}
