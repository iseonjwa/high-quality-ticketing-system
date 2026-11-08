package com.iseonjwa.reservation.seathold.concurrency;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 좌석 선점 부하 시나리오 L1·L2·L3·L6의 로컬 근사(docs/design/reservation/load-test-scenarios.md 3절).
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
                "spring.datasource.hikari.connection-timeout=3000"})
@ActiveProfiles("test")
class SeatHoldLoadTest {
    // Gateway 전체 타임아웃(SA 9-4)과 같게 둔다. 넘으면 타임아웃 오류로 센다
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration SAMPLE_INTERVAL = Duration.ofMillis(50);

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DataSource dataSource;
    @Value("${spring.datasource.url}")
    String dbUrl;
    @Value("${spring.datasource.username}")
    String dbUser;
    @Value("${spring.datasource.password}")
    String dbPassword;

    private final AtomicLong userSequence = new AtomicLong(800_000_000L);
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
        var scheduleId = fixture.createOnSaleSchedule();
        var seats = fixture.createSeatsInBulk(scheduleId, 15_000);
        var result = run(scheduleId, 100, Duration.ofSeconds(1), 10, null, randomSeats(seats));
        report("L1", result, scheduleId, Map.of("vus", 100));
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

    record RunResult(List<Sample> samples, int maxPendingConnections, int maxLockWaiters) {
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
        var samples = new ConcurrentLinkedQueue<Sample>();
        var running = new AtomicBoolean(true);
        var maxPending = new AtomicInteger();
        var maxLockWaiters = new AtomicInteger();
        var sampler = Thread.ofPlatform().start(() -> sample(running, maxPending, maxLockWaiters));
        var deadline = duration == null ? Long.MAX_VALUE : System.nanoTime() + ramp.toNanos() + duration.toNanos();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var vu = 0; vu < vus; vu++) {
                var delayNanos = vus == 1 ? 0 : ramp.toNanos() * vu / (vus - 1);
                executor.submit(() -> {
                    TimeUnit.NANOSECONDS.sleep(delayNanos);
                    var random = ThreadLocalRandom.current();
                    for (var i = 0; iterations > 0 ? i < iterations : System.nanoTime() < deadline; i++) {
                        samples.add(send(scheduleId, chooser.apply(random)));
                    }
                    return null;
                });
            }
        } finally {
            running.set(false);
            sampler.join();
        }
        return new RunResult(new ArrayList<>(samples), maxPending.get(), maxLockWaiters.get());
    }

    private Sample send(Long scheduleId, List<Long> seatIds) {
        var body = "{\"seatIds\":" + seatIds + "}";
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/schedules/" + scheduleId
                        + "/reservations"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("X-User-Id", String.valueOf(userSequence.incrementAndGet()))
                .header("X-User-Role", "USER")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        var start = System.nanoTime();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            var status = String.valueOf(response.statusCode());
            if (response.statusCode() == 409) {
                status = "409:" + errorCode(response.body());
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
    private void sample(AtomicBoolean running, AtomicInteger maxPending, AtomicInteger maxLockWaiters) {
        try (var connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
                var statement = connection.prepareStatement("SELECT count(*) FROM pg_stat_activity"
                        + " WHERE datname = current_database() AND wait_event_type = 'Lock'")) {
            var pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
            while (running.get()) {
                maxPending.accumulateAndGet(pool.getThreadsAwaitingConnection(), Math::max);
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
