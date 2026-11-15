package com.iseonjwa.reservation.queue.infrastructure;

import com.iseonjwa.reservation.queue.domain.EnterOutcome;
import com.iseonjwa.reservation.queue.domain.IssueTokenOutcome;
import com.iseonjwa.reservation.queue.domain.PositionOutcome;
import com.iseonjwa.reservation.queue.domain.QueueStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;

import static java.time.temporal.ChronoUnit.DAYS;
import static com.iseonjwa.reservation.queue.domain.EnterOutcome.Type;
import static org.junit.jupiter.api.Assertions.*;

@DataRedisTest
@ActiveProfiles("test")
// 슬라이스 테스트는 @Repository와 차단기 설정을 자동으로 안 띄워서 직접 넣어야 함
@Import({RedisQueueRepository.class, QueueRedisCircuitBreakerConfiguration.class})
class RedisQueueRepositoryTest {

    static final long SCHEDULE_ID = 31L;
    static final long OTHER_SCHEDULE_ID = 32L;
    static final long TOKEN_TTL_MILLIS = 600_000; // 10분
    static final long TURN_TTL_MILLIS = 60_000; // 차례 유효 시간 1분
    static final long STARTS_AT_EPOCH_MILLI = Instant.now().plus(1, DAYS).toEpochMilli(); // 공연 시작(판매 종료) 시각
    static final long KEY_EXPIRE_AT_EPOCH_SECOND = Instant.now().plus(1, DAYS).getEpochSecond();

    static final String SEQ = "queue:{" + SCHEDULE_ID + "}:seq";
    static final String WAITING = "queue:{" + SCHEDULE_ID + "}:waiting";
    static final String ADMITTED = "queue:{" + SCHEDULE_ID + "}:admitted";
    static final String ISSUED = "queue:{" + SCHEDULE_ID + "}:issued";
    static final String LAST_TICK = "queue:{" + SCHEDULE_ID + "}:lastTick";
    static final String ADMIT_LOG = "queue:{" + SCHEDULE_ID + "}:admitLog";

    @Autowired RedisQueueRepository queueRepository;
    @Autowired StringRedisTemplate stringRedisTemplate;


    @BeforeEach
    void setUp() {
        // admitKeys는 진입 키에 lastTick까지 포함한다. lastTick이 남으면 다음 테스트가 "이번 초에 이미 올렸다"로 건너뛴다
        stringRedisTemplate.delete(QueueKeys.of(SCHEDULE_ID).admitKeys());
        stringRedisTemplate.delete(QueueKeys.of(OTHER_SCHEDULE_ID).admitKeys());
    }


    // admitted가 이 값까지 올라간 시각을 ago 전으로 기록한다.
    // Lua가 Redis TIME을 쓰므로 시각을 조작할 수 없어 기록 쪽을 과거로 둔다. 테스트 시계와 Redis 시계 차이를 생각해 30초·90초처럼 여유를 둔다
    void turnCameAgo(long admitted, Duration ago) {
        stringRedisTemplate.opsForValue().set(ADMITTED, String.valueOf(admitted));
        stringRedisTemplate.opsForZSet().add(ADMIT_LOG,
                String.valueOf(System.currentTimeMillis() - ago.toMillis()), admitted);
    }

    EnterOutcome enter(long userId) {
        return queueRepository.enter(SCHEDULE_ID, userId, 0, STARTS_AT_EPOCH_MILLI,
                TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND, TURN_TTL_MILLIS);
    }

    PositionOutcome position(long userId) {
        return queueRepository.position(SCHEDULE_ID, userId, TOKEN_TTL_MILLIS, TURN_TTL_MILLIS);
    }


    @Nested
    class 진입 {

        @Test
        void 처음_진입하면_새_번호를_받는다() {
            EnterOutcome o = queueRepository.enter(SCHEDULE_ID, 42, 0, STARTS_AT_EPOCH_MILLI, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND, TURN_TTL_MILLIS);
            assertEquals(Type.JOINED, o.type());
            assertEquals(1L, o.seq());
        }

        @Test
        void 다시_진입하면_같은_번호다() {
            queueRepository.enter(SCHEDULE_ID, 42, 0, STARTS_AT_EPOCH_MILLI, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND, TURN_TTL_MILLIS);
            EnterOutcome o = queueRepository.enter(SCHEDULE_ID, 42, 0, STARTS_AT_EPOCH_MILLI, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND, TURN_TTL_MILLIS);
            assertEquals(Type.WAITING, o.type());
            assertEquals(1L, o.seq());
        }

        @Test
        void 같은_사용자가_동시에_10번_진입해도_seq는_하나다() throws Exception {
            int threads = 10;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);   // 10개 스레드가 모두 준비됐는지
            CountDownLatch start = new CountDownLatch(1);         // 동시에 출발시키는 신호

            try {
                List<Future<EnterOutcome>> futures = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    futures.add(pool.submit(() -> {
                        ready.countDown();   // "나 준비됐어"
                        start.await();       // 출발 신호가 올 때까지 대기
                        return queueRepository.enter(SCHEDULE_ID, 42, 0, STARTS_AT_EPOCH_MILLI, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND, TURN_TTL_MILLIS);
                    }));
                }

                ready.await();       // 10개가 전부 출발선에 설 때까지 기다림
                start.countDown();   // 동시에 출발

                List<EnterOutcome> outcomes = new ArrayList<>();
                for (Future<EnterOutcome> f : futures) {
                    outcomes.add(f.get(5, TimeUnit.SECONDS));   // 스레드 안에서 난 예외도 여기서 드러남
                }

                // 1. Redis의 번호표 카운터가 1번만 올랐다
                assertEquals("1", stringRedisTemplate.opsForValue().get("queue:{" + SCHEDULE_ID + "}:seq"));
                // 2. 새로 줄 선 건 딱 한 번, 나머지 9번은 기존 자리
                assertEquals(1, outcomes.stream().filter(o -> o.type() == Type.JOINED).count());
                // 3. 모두 같은 1번 자리를 받았다
                for (EnterOutcome o : outcomes) {
                    assertEquals(1L, o.seq());
                }
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        void 여러_사용자가_동시에_진입해도_seq가_겹치지_않는다() throws Exception {
            List<Callable<EnterOutcome>> tasks = new ArrayList<>();
            for (long userId = 1; userId <= 10; userId++) {
                long u = userId;
                tasks.add(() -> queueRepository.enter(SCHEDULE_ID, u, 0, STARTS_AT_EPOCH_MILLI, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND, TURN_TTL_MILLIS));
            }

            try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
                Set<Long> seqs = new HashSet<>();
                for (Future<EnterOutcome> f : pool.invokeAll(tasks)) {
                    seqs.add(f.get().seq());
                }
                assertEquals(Set.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L), seqs);
            }
        }

        @Test
        void 회차가_다르면_번호가_따로_매겨진다() {
            EnterOutcome a = queueRepository.enter(SCHEDULE_ID, 42, 0, STARTS_AT_EPOCH_MILLI, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND, TURN_TTL_MILLIS);
            EnterOutcome b = queueRepository.enter(OTHER_SCHEDULE_ID, 42, 0, STARTS_AT_EPOCH_MILLI, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND, TURN_TTL_MILLIS);

            assertEquals(1L, a.seq());
            assertEquals(1L, b.seq());
        }

        @Test
        void 토큰을_받은_사용자는_새_번호_없이_READY() {
            long now = System.currentTimeMillis();
            stringRedisTemplate.opsForZSet().add(ISSUED, "42", now);

            EnterOutcome o = queueRepository.enter(SCHEDULE_ID, 42, 0, STARTS_AT_EPOCH_MILLI, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND, TURN_TTL_MILLIS);

            assertEquals(Type.READY, o.type());
            assertNull(o.seq());
            assertFalse(stringRedisTemplate.hasKey(SEQ));
        }

        @Test
        void 토큰을_받은_사용자의_waiting_잔여는_지운다() {
            // 토큰 발급 중간 실패로 issued와 waiting 둘 다에 남음
            long now = System.currentTimeMillis();
            stringRedisTemplate.opsForZSet().add(ISSUED, "42", now);
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 5);

            EnterOutcome r = queueRepository.enter(SCHEDULE_ID, 42, 0, STARTS_AT_EPOCH_MILLI, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND, TURN_TTL_MILLIS);

            assertEquals(Type.READY, r.type());
            assertNull(stringRedisTemplate.opsForZSet().score(WAITING, "42"));    // 대기 자리 정리됨
        }

        @Test
        void 만료된_issued_기록과_waiting_잔여가_있으면_새_번호를_받는다() {
            // 11분 전에 토큰을 받았고(만료), 옛 대기 자리 5번이 남아 있음, 번호표는 5번까지 나감
            long elevenMinutesAgo = System.currentTimeMillis() - Duration.ofMinutes(11).toMillis();
            stringRedisTemplate.opsForZSet().add(ISSUED, "42", elevenMinutesAgo);
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 5);
            stringRedisTemplate.opsForValue().set(SEQ, "5");

            EnterOutcome o = queueRepository.enter(SCHEDULE_ID, 42, 0, STARTS_AT_EPOCH_MILLI, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND, TURN_TTL_MILLIS);

            assertEquals(Type.JOINED, o.type());
            assertEquals(6L, o.seq());
            assertNull(stringRedisTemplate.opsForZSet().score(ISSUED, "42"));
            assertEquals(6.0, stringRedisTemplate.opsForZSet().score(WAITING, "42"));
        }

        @Test
        void 차례가_온_지_1분_안이면_READY이고_자리는_그대로다() {
            stringRedisTemplate.opsForValue().set(SEQ, "10");
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 5);
            turnCameAgo(10, Duration.ofSeconds(30));

            EnterOutcome o = enter(42);

            assertEquals(Type.READY, o.type());
            assertNull(o.seq());
            assertEquals(5.0, stringRedisTemplate.opsForZSet().score(WAITING, "42"));
        }

        @Test
        void 차례가_온_지_1분이_넘으면_새_번호로_줄_맨_뒤에_선다() {
            stringRedisTemplate.opsForValue().set(SEQ, "10");
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 5);
            turnCameAgo(10, Duration.ofSeconds(90));

            EnterOutcome o = enter(42);

            assertEquals(Type.JOINED, o.type());
            assertEquals(11L, o.seq());
            assertEquals(10L, o.admitted());
            assertEquals(11.0, stringRedisTemplate.opsForZSet().score(WAITING, "42"));
        }

        @Test
        void 차례_전이면_오래_지나도_WAITING이고_번호_그대로다() {
            stringRedisTemplate.opsForValue().set(SEQ, "20");
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 15);
            turnCameAgo(10, Duration.ofMinutes(10)); // 10번까지만 차례가 옴

            EnterOutcome o = enter(42);

            assertEquals(Type.WAITING, o.type());
            assertEquals(15L, o.seq());
            assertEquals(10L, o.admitted());
        }

        @Test
        void 차례가_온_기록이_없으면_유효로_보고_READY() {
            // 배포 전부터 있던 키나 기록 누락에 대비한 정책 (ADR 0031 결정 11)
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 5);
            stringRedisTemplate.opsForValue().set(ADMITTED, "10");

            assertEquals(Type.READY, enter(42).type());
        }

        @Test
        void 차례가_온_시각은_seq_이상으로_처음_올라간_기록이다() {
            // 5번은 admitted가 3→10으로 오를 때 차례가 왔다. 3까지 오른 90초 전 기록이 아니라 10까지 오른 30초 전 기록을 봐야 한다
            stringRedisTemplate.opsForValue().set(SEQ, "10");
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 5);
            turnCameAgo(3, Duration.ofSeconds(90));
            turnCameAgo(10, Duration.ofSeconds(30));

            assertEquals(Type.READY, enter(42).type());
        }

        @Test
        void 오픈_전이면_SALE_NOT_OPEN() {
            long oneHourLater = System.currentTimeMillis() + Duration.ofHours(1).toMillis();

            EnterOutcome o = queueRepository.enter(SCHEDULE_ID, 42, oneHourLater, STARTS_AT_EPOCH_MILLI, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND, TURN_TTL_MILLIS);

            assertEquals(Type.SALE_NOT_OPEN, o.type());
            assertFalse(stringRedisTemplate.hasKey(SEQ));
            assertFalse(stringRedisTemplate.hasKey(WAITING));
        }

        @Test
        void 공연이_시작됐으면_SALE_CLOSED이고_줄에_넣지_않는다() {
            long oneMinuteAgo = System.currentTimeMillis() - Duration.ofMinutes(1).toMillis();

            EnterOutcome o = queueRepository.enter(SCHEDULE_ID, 42, 0, oneMinuteAgo, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND, TURN_TTL_MILLIS);

            assertEquals(Type.SALE_CLOSED, o.type());
            assertFalse(stringRedisTemplate.hasKey(SEQ));
            assertFalse(stringRedisTemplate.hasKey(WAITING));
        }

        @Test
        void 공연이_시작됐으면_유효한_토큰이_있어도_SALE_CLOSED() {
            // 판매 종료 판정이 토큰 기록 확인보다 먼저다
            long oneMinuteAgo = System.currentTimeMillis() - Duration.ofMinutes(1).toMillis();
            stringRedisTemplate.opsForZSet().add(ISSUED, "42", System.currentTimeMillis());

            EnterOutcome o = queueRepository.enter(SCHEDULE_ID, 42, 0, oneMinuteAgo, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND, TURN_TTL_MILLIS);

            assertEquals(Type.SALE_CLOSED, o.type());
            assertNotNull(stringRedisTemplate.opsForZSet().score(ISSUED, "42")); // 기록은 건드리지 않음
        }

        @Test
        void 새로_받은_seq는_admitted보다_크다() {
            // 100번까지 번호가 나갔고 100번까지 입장시킨 상태 (줄이 빈 상태)
            stringRedisTemplate.opsForValue().set(SEQ, "100");
            stringRedisTemplate.opsForValue().set(ADMITTED, "100");

            EnterOutcome o = queueRepository.enter(SCHEDULE_ID, 42, 0, STARTS_AT_EPOCH_MILLI, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND, TURN_TTL_MILLIS);

            assertEquals(Type.JOINED, o.type());
            assertTrue(o.seq() > o.admitted()); // 101 > 100
        }

        @Test
        void 새로_줄_서면_seq와_waiting에_만료가_걸린다() {
            queueRepository.enter(SCHEDULE_ID, 42, 0, STARTS_AT_EPOCH_MILLI, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND, TURN_TTL_MILLIS);

            assertTrue(stringRedisTemplate.getExpire(SEQ) > 0); // -1이면 만료가 없는 상태
            assertTrue(stringRedisTemplate.getExpire(WAITING) > 0);
        }
    }


    @Nested
    class 순번_조회 {

        @Test
        void 줄에_없으면_NOT_JOINED() {
            PositionOutcome o = queueRepository.position(SCHEDULE_ID, 42, TOKEN_TTL_MILLIS, TURN_TTL_MILLIS);
            assertEquals(PositionOutcome.Type.NOT_JOINED, o.type());
            assertNull(o.seq());
        }

        @Test
        void 조회는_키를_만들지_않는다() {
            queueRepository.position(SCHEDULE_ID, 42, TOKEN_TTL_MILLIS, TURN_TTL_MILLIS);
            assertFalse(stringRedisTemplate.hasKey(WAITING));
            assertFalse(stringRedisTemplate.hasKey(ADMITTED));
            assertFalse(stringRedisTemplate.hasKey(ISSUED));
        }

        @Test
        void 차례_전이면_WAITING과_seq_admitted를_준다() {
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 5);
            stringRedisTemplate.opsForValue().set(ADMITTED, "3");

            PositionOutcome o = queueRepository.position(SCHEDULE_ID, 42, TOKEN_TTL_MILLIS, TURN_TTL_MILLIS);

            assertEquals(PositionOutcome.Type.WAITING, o.type());
            assertEquals(5L, o.seq());
            assertEquals(3L, o.admitted());
        }

        @Test
        void admitted가_아직_없으면_0으로_준다() {
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 1);
            PositionOutcome o = queueRepository.position(SCHEDULE_ID, 42, TOKEN_TTL_MILLIS, TURN_TTL_MILLIS);
            assertEquals(0L, o.admitted());
        }

        @Test
        void 진입한_뒤_조회하면_진입_때와_같은_seq다() {
            EnterOutcome entered = queueRepository.enter(SCHEDULE_ID, 42, 0, STARTS_AT_EPOCH_MILLI, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND, TURN_TTL_MILLIS);
            PositionOutcome o = queueRepository.position(SCHEDULE_ID, 42, TOKEN_TTL_MILLIS, TURN_TTL_MILLIS);
            assertEquals(PositionOutcome.Type.WAITING, o.type());
            assertEquals(entered.seq(), o.seq());
        }

        @Test
        void 토큰이_유효하면_READY() {
            stringRedisTemplate.opsForZSet().add(ISSUED, "42", System.currentTimeMillis());
            PositionOutcome o = queueRepository.position(SCHEDULE_ID, 42, TOKEN_TTL_MILLIS, TURN_TTL_MILLIS);
            assertEquals(PositionOutcome.Type.READY, o.type());
        }

        @Test
        void 토큰이_유효하고_waiting_잔여가_있어도_READY이고_잔여는_지우지_않는다() {
            stringRedisTemplate.opsForZSet().add(ISSUED, "42", System.currentTimeMillis());
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 5);

            PositionOutcome o = queueRepository.position(SCHEDULE_ID, 42, TOKEN_TTL_MILLIS, TURN_TTL_MILLIS);

            assertEquals(PositionOutcome.Type.READY, o.type());
            assertEquals(5.0, stringRedisTemplate.opsForZSet().score(WAITING, "42"));
        }

        @Test
        void 토큰이_만료되고_waiting_잔여가_있으면_옛_자리를_보지_않고_NOT_JOINED() {
            // 옛 자리 5번은 이미 차례가 지남(admitted 10). waiting을 봤다면 READY가 나갔을 상황
            long elevenMinutesAgo = System.currentTimeMillis() - Duration.ofMinutes(11).toMillis();
            stringRedisTemplate.opsForZSet().add(ISSUED, "42", elevenMinutesAgo);
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 5);
            stringRedisTemplate.opsForValue().set(ADMITTED, "10");

            PositionOutcome o = queueRepository.position(SCHEDULE_ID, 42, TOKEN_TTL_MILLIS, TURN_TTL_MILLIS);

            assertEquals(PositionOutcome.Type.NOT_JOINED, o.type());
            assertNotNull(stringRedisTemplate.opsForZSet().score(ISSUED, "42"));
            assertNotNull(stringRedisTemplate.opsForZSet().score(WAITING, "42"));
        }

        @Test
        void 차례가_온_지_1분_안이면_READY() {
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 5);
            turnCameAgo(10, Duration.ofSeconds(30));

            PositionOutcome o = position(42);

            assertEquals(PositionOutcome.Type.READY, o.type());
            assertNull(o.seq());
        }

        @Test
        void 차례가_온_지_1분이_넘으면_NOT_JOINED이고_waiting은_지우지_않는다() {
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 5);
            turnCameAgo(10, Duration.ofSeconds(90));

            assertEquals(PositionOutcome.Type.NOT_JOINED, position(42).type());
            assertEquals(5.0, stringRedisTemplate.opsForZSet().score(WAITING, "42")); // 읽기 전용 (ADR 0031 결정 4)
        }

        @Test
        void 차례가_온_기록이_없으면_유효로_보고_READY() {
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 5);
            stringRedisTemplate.opsForValue().set(ADMITTED, "10");

            assertEquals(PositionOutcome.Type.READY, position(42).type());
        }

        @Test
        void 다른_회차의_자리는_보이지_않는다() {
            queueRepository.enter(OTHER_SCHEDULE_ID, 42, 0, STARTS_AT_EPOCH_MILLI, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND, TURN_TTL_MILLIS);
            PositionOutcome o = queueRepository.position(SCHEDULE_ID, 42, TOKEN_TTL_MILLIS, TURN_TTL_MILLIS);
            assertEquals(PositionOutcome.Type.NOT_JOINED, o.type());
        }
    }

    @Nested
    class 입장_처리 {

        static final int ADMIT_PER_SECOND = 50;
        static final int CLEANUP_LIMIT = 500;

        void admit() {
            admit(CLEANUP_LIMIT);
        }

        void admit(int cleanupLimit) {
            queueRepository.admit(SCHEDULE_ID, ADMIT_PER_SECOND, TOKEN_TTL_MILLIS, cleanupLimit, KEY_EXPIRE_AT_EPOCH_SECOND);
        }

        String get(String key) {
            return stringRedisTemplate.opsForValue().get(key);
        }

        @Test
        void 줄이_있으면_초당_입장_인원만큼_오른다() {
            stringRedisTemplate.opsForValue().set(SEQ, "100");

            admit();

            assertEquals("50", get(ADMITTED));
            assertNotNull(get(LAST_TICK));
        }

        @Test
        void 이전_admitted에서_이어서_오른다() {
            stringRedisTemplate.opsForValue().set(SEQ, "200");
            stringRedisTemplate.opsForValue().set(ADMITTED, "50");
            stringRedisTemplate.opsForValue().set(LAST_TICK, "1"); // 아주 예전 초

            admit();

            assertEquals("100", get(ADMITTED));
        }

        @Test
        void 발급된_seq를_넘지_않는다() {
            // 줄에 30명뿐이면 50을 더해도 30까지만 (줄이 빈 동안 앞서가지 않음)
            stringRedisTemplate.opsForValue().set(SEQ, "30");

            admit();

            assertEquals("30", get(ADMITTED));
        }

        @Test
        void 이번_초에_이미_올렸으면_다시_올리지_않는다() throws InterruptedException {
            // 다른 인스턴스가 이번 초에 이미 올린 상황. 시각은 Lua와 같은 Redis TIME 기준으로 맞춘다
            // 초가 끝나기 직전이면 admit() 사이에 초가 넘어가므로, 다음 초 앞부분까지 기다린다
            long redisNowMillis = redisTimeMillis();
            if (redisNowMillis % 1000 > 500) {
                Thread.sleep(1000 - redisNowMillis % 1000 + 10);
                redisNowMillis = redisTimeMillis();
            }
            stringRedisTemplate.opsForValue().set(SEQ, "100");
            stringRedisTemplate.opsForValue().set(ADMITTED, "50");
            stringRedisTemplate.opsForValue().set(LAST_TICK, String.valueOf(redisNowMillis / 1000));

            admit();

            assertEquals("50", get(ADMITTED));
        }

        long redisTimeMillis() {
            return stringRedisTemplate.execute(
                    (RedisCallback<Long>) connection -> connection.serverCommands().time());
        }

        @Test
        void 진입한_사람이_없으면_키를_만들지_않는다() {
            admit();

            assertFalse(stringRedisTemplate.hasKey(ADMITTED));
            assertFalse(stringRedisTemplate.hasKey(LAST_TICK));
        }

        @Test
        void admitted가_커지면_차례가_온_시각을_기록하고_만료를_건다() {
            stringRedisTemplate.opsForValue().set(SEQ, "100");

            admit();

            assertEquals(1, stringRedisTemplate.opsForZSet().rangeByScore(ADMIT_LOG, ADMIT_PER_SECOND, ADMIT_PER_SECOND).size());
            assertTrue(stringRedisTemplate.getExpire(ADMIT_LOG) > 0);
        }

        @Test
        void 상한에_막혀_admitted가_그대로면_기록하지_않는다() {
            stringRedisTemplate.opsForValue().set(SEQ, "10");
            stringRedisTemplate.opsForValue().set(ADMITTED, "10");

            admit();

            assertEquals(0L, stringRedisTemplate.opsForZSet().zCard(ADMIT_LOG));
        }

        @Test
        void 만료된_issued와_그_사람의_waiting_잔여를_함께_지우고_유효한_issued는_남긴다() {
            long elevenMinutesAgo = System.currentTimeMillis() - Duration.ofMinutes(11).toMillis();
            stringRedisTemplate.opsForValue().set(SEQ, "10");
            stringRedisTemplate.opsForZSet().add(ISSUED, "42", elevenMinutesAgo);           // 만료
            stringRedisTemplate.opsForZSet().add(ISSUED, "7", System.currentTimeMillis());   // 유효
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 3);                         // 만료자의 잔여
            stringRedisTemplate.opsForZSet().add(WAITING, "7", 4);                          // 유효자의 잔여
            stringRedisTemplate.opsForZSet().add(WAITING, "9", 8);                          // 아직 대기 중

            admit();

            assertNull(stringRedisTemplate.opsForZSet().score(ISSUED, "42"));
            assertNull(stringRedisTemplate.opsForZSet().score(WAITING, "42"));
            assertNotNull(stringRedisTemplate.opsForZSet().score(ISSUED, "7"));
            assertNotNull(stringRedisTemplate.opsForZSet().score(WAITING, "7"));
            assertNotNull(stringRedisTemplate.opsForZSet().score(WAITING, "9"));
        }

        @Test
        void 한_번에_최대_정리_인원까지만_지운다() {
            long elevenMinutesAgo = System.currentTimeMillis() - Duration.ofMinutes(11).toMillis();
            stringRedisTemplate.opsForValue().set(SEQ, "10");
            stringRedisTemplate.opsForZSet().add(ISSUED, "1", elevenMinutesAgo);
            stringRedisTemplate.opsForZSet().add(ISSUED, "2", elevenMinutesAgo + 1);
            stringRedisTemplate.opsForZSet().add(ISSUED, "3", elevenMinutesAgo + 2);

            admit(2);

            assertEquals(1L, stringRedisTemplate.opsForZSet().zCard(ISSUED));
            assertNotNull(stringRedisTemplate.opsForZSet().score(ISSUED, "3")); // 가장 늦게 발급된 1명이 남음
        }

        @Test
        void admitted와_lastTick에_만료가_걸린다() {
            stringRedisTemplate.opsForValue().set(SEQ, "100");

            admit();

            assertTrue(stringRedisTemplate.getExpire(ADMITTED) > 0); // -1이면 만료가 없는 상태
            assertTrue(stringRedisTemplate.getExpire(LAST_TICK) > 0);
        }

        @Test
        void 다른_회차는_올리지_않는다() {
            stringRedisTemplate.opsForValue().set(SEQ, "100");
            stringRedisTemplate.opsForValue().set("queue:{" + OTHER_SCHEDULE_ID + "}:seq", "100");

            admit();

            assertFalse(stringRedisTemplate.hasKey("queue:{" + OTHER_SCHEDULE_ID + "}:admitted"));
        }
    }

    @Nested
    class 토큰_발급 {

        IssueTokenOutcome issue(long userId) {
            return queueRepository.issueToken(SCHEDULE_ID, userId, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND, TURN_TTL_MILLIS);
        }

        @Test
        void 차례가_된_사용자는_토큰을_받고_waiting에서_빠진다() {
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 5);
            stringRedisTemplate.opsForValue().set(ADMITTED, "10");

            IssueTokenOutcome o = issue(42);

            assertEquals(IssueTokenOutcome.Type.ADMITTED, o.type());
            assertNotNull(o.issuedAt());
            assertEquals(Double.valueOf(o.issuedAt()), stringRedisTemplate.opsForZSet().score(ISSUED, "42"));
            assertNull(stringRedisTemplate.opsForZSet().score(WAITING, "42"));
        }

        @Test
        void 다시_요청하면_같은_발급_시각을_돌려준다() {
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 5);
            stringRedisTemplate.opsForValue().set(ADMITTED, "10");

            IssueTokenOutcome first = issue(42);
            IssueTokenOutcome second = issue(42);

            assertEquals(IssueTokenOutcome.Type.ADMITTED, second.type());
            assertEquals(first.issuedAt(), second.issuedAt());
        }

        @Test
        void 차례_전이면_WAITING이고_issued에_쓰지_않는다() {
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 15);
            stringRedisTemplate.opsForValue().set(ADMITTED, "10");

            IssueTokenOutcome o = issue(42);

            assertEquals(IssueTokenOutcome.Type.WAITING, o.type());
            assertNull(stringRedisTemplate.opsForZSet().score(ISSUED, "42"));
            assertNotNull(stringRedisTemplate.opsForZSet().score(WAITING, "42"));
        }

        @Test
        void admitted가_없으면_WAITING() {
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 1);

            assertEquals(IssueTokenOutcome.Type.WAITING, issue(42).type());
        }

        @Test
        void 줄에_없으면_NOT_JOINED() {
            assertEquals(IssueTokenOutcome.Type.NOT_JOINED, issue(42).type());
        }

        @Test
        void 유효한_토큰이_있으면_저장된_시각을_주고_waiting_잔여를_지운다() {
            long issuedAt = System.currentTimeMillis() - Duration.ofMinutes(1).toMillis();
            stringRedisTemplate.opsForZSet().add(ISSUED, "42", issuedAt);
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 5); // 진입 Lua가 남긴 잔여

            IssueTokenOutcome o = issue(42);

            assertEquals(IssueTokenOutcome.Type.ADMITTED, o.type());
            assertEquals(issuedAt, o.issuedAt().longValue());
            assertNull(stringRedisTemplate.opsForZSet().score(WAITING, "42"));
        }

        @Test
        void 만료된_토큰이면_NOT_JOINED이고_issued와_waiting을_모두_지운다() {
            long elevenMinutesAgo = System.currentTimeMillis() - Duration.ofMinutes(11).toMillis();
            stringRedisTemplate.opsForZSet().add(ISSUED, "42", elevenMinutesAgo);
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 5);
            stringRedisTemplate.opsForValue().set(ADMITTED, "10");

            IssueTokenOutcome o = issue(42);

            assertEquals(IssueTokenOutcome.Type.NOT_JOINED, o.type());
            assertNull(stringRedisTemplate.opsForZSet().score(ISSUED, "42"));
            assertNull(stringRedisTemplate.opsForZSet().score(WAITING, "42"));
        }

        @Test
        void issued에_만료가_걸린다() {
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 5);
            stringRedisTemplate.opsForValue().set(ADMITTED, "10");

            issue(42);

            assertTrue(stringRedisTemplate.getExpire(ISSUED) > 0);
        }

        @Test
        void 차례가_온_지_1분_안이면_토큰을_받는다() {
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 5);
            turnCameAgo(10, Duration.ofSeconds(30));

            IssueTokenOutcome o = issue(42);

            assertEquals(IssueTokenOutcome.Type.ADMITTED, o.type());
            assertNotNull(stringRedisTemplate.opsForZSet().score(ISSUED, "42"));
        }

        @Test
        void 차례가_온_지_1분이_넘으면_NOT_JOINED이고_waiting에서_지운다() {
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 5);
            turnCameAgo(10, Duration.ofSeconds(90));

            IssueTokenOutcome o = issue(42);

            assertEquals(IssueTokenOutcome.Type.NOT_JOINED, o.type());
            assertNull(stringRedisTemplate.opsForZSet().score(WAITING, "42"));
            assertNull(stringRedisTemplate.opsForZSet().score(ISSUED, "42"));
        }

        @Test
        void 유효한_토큰이_있으면_차례_시간과_상관없이_같은_발급_시각을_준다() {
            long issuedAt = System.currentTimeMillis();
            stringRedisTemplate.opsForZSet().add(ISSUED, "42", issuedAt);
            turnCameAgo(10, Duration.ofSeconds(90));

            IssueTokenOutcome o = issue(42);

            assertEquals(IssueTokenOutcome.Type.ADMITTED, o.type());
            assertEquals(issuedAt, o.issuedAt().longValue());
        }

        @Test
        void 다른_회차의_줄은_보지_않는다() {
            stringRedisTemplate.opsForZSet().add("queue:{" + OTHER_SCHEDULE_ID + "}:waiting", "42", 1);
            stringRedisTemplate.opsForValue().set("queue:{" + OTHER_SCHEDULE_ID + "}:admitted", "10");

            assertEquals(IssueTokenOutcome.Type.NOT_JOINED, issue(42).type());
        }
    }

    // 지표용 통계는 읽기만 한다 (ISE-61)
    @Nested
    class 통계 {

        QueueStats stats() {
            return queueRepository.stats(SCHEDULE_ID, TOKEN_TTL_MILLIS);
        }

        @Test
        void 키가_없으면_모두_0이다() {
            assertEquals(QueueStats.EMPTY, stats());
        }

        @Test
        void 차례_전과_후를_admitted를_경계로_나눈다() {
            stringRedisTemplate.opsForValue().set(SEQ, "5");
            stringRedisTemplate.opsForValue().set(ADMITTED, "3");
            for (int seq = 1; seq <= 5; seq++) {
                stringRedisTemplate.opsForZSet().add(WAITING, String.valueOf(40 + seq), seq);
            }

            QueueStats s = stats();

            assertEquals(5, s.joined());
            assertEquals(3, s.admitted());
            assertEquals(2, s.waiting());          // 4, 5번
            assertEquals(3, s.admittedUnissued()); // 1, 2, 3번 (admitted 번호 포함)
        }

        @Test
        void 토큰을_받으면_waiting에서_빠져_발급_안_된_인원에_들지_않는다() {
            stringRedisTemplate.opsForValue().set(SEQ, "2");
            stringRedisTemplate.opsForValue().set(ADMITTED, "2");
            stringRedisTemplate.opsForZSet().add(WAITING, "41", 1);
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 2);
            queueRepository.issueToken(SCHEDULE_ID, 42, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND, TURN_TTL_MILLIS);

            QueueStats s = stats();

            assertEquals(1, s.admittedUnissued()); // 41번만
            assertEquals(1, s.activeTokens());     // 42번
        }

        @Test
        void 만료된_토큰은_유효_토큰에_세지_않는다() {
            long now = System.currentTimeMillis();
            stringRedisTemplate.opsForZSet().add(ISSUED, "41", now - Duration.ofMinutes(1).toMillis());
            stringRedisTemplate.opsForZSet().add(ISSUED, "42", now - Duration.ofMinutes(11).toMillis());

            assertEquals(1, stats().activeTokens());
        }

        @Test
        void 읽기만_하고_아무것도_바꾸지_않는다() {
            stringRedisTemplate.opsForValue().set(SEQ, "2");
            stringRedisTemplate.opsForValue().set(ADMITTED, "1");
            stringRedisTemplate.opsForZSet().add(WAITING, "41", 1);
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 2);

            stats();

            assertEquals("1", stringRedisTemplate.opsForValue().get(ADMITTED));
            assertEquals(2L, stringRedisTemplate.opsForZSet().zCard(WAITING));
            assertNull(stringRedisTemplate.opsForValue().get(LAST_TICK));
        }

        @Test
        void 다른_회차는_세지_않는다() {
            stringRedisTemplate.opsForValue().set("queue:{" + OTHER_SCHEDULE_ID + "}:seq", "9");

            assertEquals(0, stats().joined());
        }
    }

    // 선점에 쓴 토큰의 발급 기록 읽기·지우기 (ISE-207)
    @Nested
    class 토큰_정리 {

        static final long ISSUED_AT = 1_793_176_230_123L; // ms. double로 저장돼도 그대로 돌아오는지 본다

        @Test
        void 발급_시각을_ms_그대로_읽는다() {
            stringRedisTemplate.opsForZSet().add(ISSUED, "42", ISSUED_AT);

            assertEquals(Optional.of(ISSUED_AT), queueRepository.issuedAt(SCHEDULE_ID, 42));
        }

        @Test
        void 발급_기록이_없으면_빈_값이다() {
            assertEquals(Optional.empty(), queueRepository.issuedAt(SCHEDULE_ID, 42));
        }

        @Test
        void 발급_시각이_같으면_지운다() {
            stringRedisTemplate.opsForZSet().add(ISSUED, "42", ISSUED_AT);

            queueRepository.discardToken(SCHEDULE_ID, 42, ISSUED_AT);

            assertNull(stringRedisTemplate.opsForZSet().score(ISSUED, "42"));
        }

        @Test
        void 발급_시각이_다르면_새로_받은_기록이라_지우지_않는다() {
            stringRedisTemplate.opsForZSet().add(ISSUED, "42", ISSUED_AT + 60_000);

            queueRepository.discardToken(SCHEDULE_ID, 42, ISSUED_AT);

            assertEquals(Double.valueOf(ISSUED_AT + 60_000), stringRedisTemplate.opsForZSet().score(ISSUED, "42"));
        }

        @Test
        void 발급_기록이_없어도_오류_없이_끝난다() {
            assertDoesNotThrow(() -> queueRepository.discardToken(SCHEDULE_ID, 42, ISSUED_AT)); // Lua가 nil을 돌려줘도 된다
        }

        @Test
        void 같은_시각에_받은_다른_사용자는_지우지_않는다() {
            stringRedisTemplate.opsForZSet().add(ISSUED, "42", ISSUED_AT);
            stringRedisTemplate.opsForZSet().add(ISSUED, "43", ISSUED_AT);

            queueRepository.discardToken(SCHEDULE_ID, 42, ISSUED_AT);

            assertEquals(Double.valueOf(ISSUED_AT), stringRedisTemplate.opsForZSet().score(ISSUED, "43"));
        }

        @Test
        void 다른_회차의_기록은_지우지_않는다() {
            String otherIssued = "queue:{" + OTHER_SCHEDULE_ID + "}:issued";
            stringRedisTemplate.opsForZSet().add(otherIssued, "42", ISSUED_AT);

            queueRepository.discardToken(SCHEDULE_ID, 42, ISSUED_AT);

            assertEquals(Double.valueOf(ISSUED_AT), stringRedisTemplate.opsForZSet().score(otherIssued, "42"));
        }
    }
}
