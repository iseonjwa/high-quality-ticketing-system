package com.iseonjwa.reservation.queue.infrastructure;

import com.iseonjwa.reservation.queue.domain.EnterOutcome;
import com.iseonjwa.reservation.queue.domain.IssueTokenOutcome;
import com.iseonjwa.reservation.queue.domain.PositionOutcome;
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
    static final long STARTS_AT_EPOCH_MILLI = Instant.now().plus(1, DAYS).toEpochMilli(); // 공연 시작(판매 종료) 시각
    static final long KEY_EXPIRE_AT_EPOCH_SECOND = Instant.now().plus(1, DAYS).getEpochSecond();

    static final String SEQ = "queue:{" + SCHEDULE_ID + "}:seq";
    static final String WAITING = "queue:{" + SCHEDULE_ID + "}:waiting";
    static final String ADMITTED = "queue:{" + SCHEDULE_ID + "}:admitted";
    static final String ISSUED = "queue:{" + SCHEDULE_ID + "}:issued";
    static final String LAST_TICK = "queue:{" + SCHEDULE_ID + "}:lastTick";

    @Autowired RedisQueueRepository queueRepository;
    @Autowired StringRedisTemplate stringRedisTemplate;


    @BeforeEach
    void setUp() {
        // admitKeys는 진입 키에 lastTick까지 포함한다. lastTick이 남으면 다음 테스트가 "이번 초에 이미 올렸다"로 건너뛴다
        stringRedisTemplate.delete(QueueKeys.of(SCHEDULE_ID).admitKeys());
        stringRedisTemplate.delete(QueueKeys.of(OTHER_SCHEDULE_ID).admitKeys());
    }


    @Nested
    class 진입 {

        @Test
        void 처음_진입하면_새_번호를_받는다() {
            EnterOutcome o = queueRepository.enter(SCHEDULE_ID, 42, 0, STARTS_AT_EPOCH_MILLI, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND);
            assertEquals(Type.JOINED, o.type());
            assertEquals(1L, o.seq());
        }

        @Test
        void 다시_진입하면_같은_번호다() {
            queueRepository.enter(SCHEDULE_ID, 42, 0, STARTS_AT_EPOCH_MILLI, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND);
            EnterOutcome o = queueRepository.enter(SCHEDULE_ID, 42, 0, STARTS_AT_EPOCH_MILLI, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND);
            assertEquals(Type.ALREADY_IN_QUEUE, o.type());
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
                        return queueRepository.enter(SCHEDULE_ID, 42, 0, STARTS_AT_EPOCH_MILLI, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND);
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
                tasks.add(() -> queueRepository.enter(SCHEDULE_ID, u, 0, STARTS_AT_EPOCH_MILLI, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND));
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
            EnterOutcome a = queueRepository.enter(SCHEDULE_ID, 42, 0, STARTS_AT_EPOCH_MILLI, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND);
            EnterOutcome b = queueRepository.enter(OTHER_SCHEDULE_ID, 42, 0, STARTS_AT_EPOCH_MILLI, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND);

            assertEquals(1L, a.seq());
            assertEquals(1L, b.seq());
        }

        @Test
        void 토큰을_받은_사용자는_새_번호_없이_TOKEN_ISSUED() {
            long now = System.currentTimeMillis();
            stringRedisTemplate.opsForZSet().add(ISSUED, "42", now);

            EnterOutcome o = queueRepository.enter(SCHEDULE_ID, 42, 0, STARTS_AT_EPOCH_MILLI, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND);

            assertEquals(Type.TOKEN_ISSUED, o.type());
            assertNull(o.seq());
            assertFalse(stringRedisTemplate.hasKey(SEQ));
        }

        @Test
        void 토큰을_받은_사용자의_waiting_잔여는_지운다() {
            // 토큰 발급 중간 실패로 issued와 waiting 둘 다에 남음
            long now = System.currentTimeMillis();
            stringRedisTemplate.opsForZSet().add(ISSUED, "42", now);
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 5);

            EnterOutcome r = queueRepository.enter(SCHEDULE_ID, 42, 0, STARTS_AT_EPOCH_MILLI, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND);

            assertEquals(Type.TOKEN_ISSUED, r.type());
            assertNull(stringRedisTemplate.opsForZSet().score(WAITING, "42"));    // 대기 자리 정리됨
        }

        @Test
        void 만료된_issued_기록과_waiting_잔여가_있으면_새_번호를_받는다() {
            // 11분 전에 토큰을 받았고(만료), 옛 대기 자리 5번이 남아 있음, 번호표는 5번까지 나감
            long elevenMinutesAgo = System.currentTimeMillis() - Duration.ofMinutes(11).toMillis();
            stringRedisTemplate.opsForZSet().add(ISSUED, "42", elevenMinutesAgo);
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 5);
            stringRedisTemplate.opsForValue().set(SEQ, "5");

            EnterOutcome o = queueRepository.enter(SCHEDULE_ID, 42, 0, STARTS_AT_EPOCH_MILLI, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND);

            assertEquals(Type.JOINED, o.type());
            assertEquals(6L, o.seq());
            assertNull(stringRedisTemplate.opsForZSet().score(ISSUED, "42"));
            assertEquals(6.0, stringRedisTemplate.opsForZSet().score(WAITING, "42"));
        }

        @Test
        void 오픈_전이면_SALE_NOT_OPEN() {
            long oneHourLater = System.currentTimeMillis() + Duration.ofHours(1).toMillis();

            EnterOutcome o = queueRepository.enter(SCHEDULE_ID, 42, oneHourLater, STARTS_AT_EPOCH_MILLI, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND);

            assertEquals(Type.SALE_NOT_OPEN, o.type());
            assertFalse(stringRedisTemplate.hasKey(SEQ));
            assertFalse(stringRedisTemplate.hasKey(WAITING));
        }

        @Test
        void 공연이_시작됐으면_SALE_CLOSED이고_줄에_넣지_않는다() {
            long oneMinuteAgo = System.currentTimeMillis() - Duration.ofMinutes(1).toMillis();

            EnterOutcome o = queueRepository.enter(SCHEDULE_ID, 42, 0, oneMinuteAgo, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND);

            assertEquals(Type.SALE_CLOSED, o.type());
            assertFalse(stringRedisTemplate.hasKey(SEQ));
            assertFalse(stringRedisTemplate.hasKey(WAITING));
        }

        @Test
        void 공연이_시작됐으면_유효한_토큰이_있어도_SALE_CLOSED() {
            // 판매 종료 판정이 토큰 기록 확인보다 먼저다
            long oneMinuteAgo = System.currentTimeMillis() - Duration.ofMinutes(1).toMillis();
            stringRedisTemplate.opsForZSet().add(ISSUED, "42", System.currentTimeMillis());

            EnterOutcome o = queueRepository.enter(SCHEDULE_ID, 42, 0, oneMinuteAgo, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND);

            assertEquals(Type.SALE_CLOSED, o.type());
            assertNotNull(stringRedisTemplate.opsForZSet().score(ISSUED, "42")); // 기록은 건드리지 않음
        }

        @Test
        void 새로_받은_seq는_admitted보다_크다() {
            // 100번까지 번호가 나갔고 100번까지 입장시킨 상태 (줄이 빈 상태)
            stringRedisTemplate.opsForValue().set(SEQ, "100");
            stringRedisTemplate.opsForValue().set(ADMITTED, "100");

            EnterOutcome o = queueRepository.enter(SCHEDULE_ID, 42, 0, STARTS_AT_EPOCH_MILLI, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND);

            assertEquals(Type.JOINED, o.type());
            assertTrue(o.seq() > o.admitted()); // 101 > 100
        }

        @Test
        void 새로_줄_서면_seq와_waiting에_만료가_걸린다() {
            queueRepository.enter(SCHEDULE_ID, 42, 0, STARTS_AT_EPOCH_MILLI, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND);

            assertTrue(stringRedisTemplate.getExpire(SEQ) > 0); // -1이면 만료가 없는 상태
            assertTrue(stringRedisTemplate.getExpire(WAITING) > 0);
        }
    }


    @Nested
    class 순번_조회 {

        @Test
        void 줄에_없으면_NOT_JOINED() {
            PositionOutcome o = queueRepository.position(SCHEDULE_ID, 42, TOKEN_TTL_MILLIS);
            assertEquals(PositionOutcome.Type.NOT_JOINED, o.type());
            assertNull(o.seq());
        }

        @Test
        void 조회는_키를_만들지_않는다() {
            queueRepository.position(SCHEDULE_ID, 42, TOKEN_TTL_MILLIS);
            assertFalse(stringRedisTemplate.hasKey(WAITING));
            assertFalse(stringRedisTemplate.hasKey(ADMITTED));
            assertFalse(stringRedisTemplate.hasKey(ISSUED));
        }

        @Test
        void 줄에_있으면_IN_QUEUE와_seq_admitted를_준다() {
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 5);
            stringRedisTemplate.opsForValue().set(ADMITTED, "3");

            PositionOutcome o = queueRepository.position(SCHEDULE_ID, 42, TOKEN_TTL_MILLIS);

            assertEquals(PositionOutcome.Type.IN_QUEUE, o.type());
            assertEquals(5L, o.seq());
            assertEquals(3L, o.admitted());
        }

        @Test
        void admitted가_아직_없으면_0으로_준다() {
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 1);
            PositionOutcome o = queueRepository.position(SCHEDULE_ID, 42, TOKEN_TTL_MILLIS);
            assertEquals(0L, o.admitted());
        }

        @Test
        void 진입한_뒤_조회하면_진입_때와_같은_seq다() {
            EnterOutcome entered = queueRepository.enter(SCHEDULE_ID, 42, 0, STARTS_AT_EPOCH_MILLI, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND);
            PositionOutcome o = queueRepository.position(SCHEDULE_ID, 42, TOKEN_TTL_MILLIS);
            assertEquals(PositionOutcome.Type.IN_QUEUE, o.type());
            assertEquals(entered.seq(), o.seq());
        }

        @Test
        void 토큰이_유효하면_TOKEN_ISSUED() {
            stringRedisTemplate.opsForZSet().add(ISSUED, "42", System.currentTimeMillis());
            PositionOutcome o = queueRepository.position(SCHEDULE_ID, 42, TOKEN_TTL_MILLIS);
            assertEquals(PositionOutcome.Type.TOKEN_ISSUED, o.type());
        }

        @Test
        void 토큰이_유효하고_waiting_잔여가_있어도_TOKEN_ISSUED이고_잔여는_지우지_않는다() {
            stringRedisTemplate.opsForZSet().add(ISSUED, "42", System.currentTimeMillis());
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 5);

            PositionOutcome o = queueRepository.position(SCHEDULE_ID, 42, TOKEN_TTL_MILLIS);

            assertEquals(PositionOutcome.Type.TOKEN_ISSUED, o.type());
            assertEquals(5.0, stringRedisTemplate.opsForZSet().score(WAITING, "42"));
        }

        @Test
        void 토큰이_만료되고_waiting_잔여가_있으면_옛_자리를_보지_않고_NOT_JOINED() {
            // 옛 자리 5번은 이미 차례가 지남(admitted 10). waiting을 봤다면 READY가 나갔을 상황
            long elevenMinutesAgo = System.currentTimeMillis() - Duration.ofMinutes(11).toMillis();
            stringRedisTemplate.opsForZSet().add(ISSUED, "42", elevenMinutesAgo);
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 5);
            stringRedisTemplate.opsForValue().set(ADMITTED, "10");

            PositionOutcome o = queueRepository.position(SCHEDULE_ID, 42, TOKEN_TTL_MILLIS);

            assertEquals(PositionOutcome.Type.NOT_JOINED, o.type());
            assertNotNull(stringRedisTemplate.opsForZSet().score(ISSUED, "42"));
            assertNotNull(stringRedisTemplate.opsForZSet().score(WAITING, "42"));
        }

        @Test
        void 다른_회차의_자리는_보이지_않는다() {
            queueRepository.enter(OTHER_SCHEDULE_ID, 42, 0, STARTS_AT_EPOCH_MILLI, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND);
            PositionOutcome o = queueRepository.position(SCHEDULE_ID, 42, TOKEN_TTL_MILLIS);
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
            return queueRepository.issueToken(SCHEDULE_ID, userId, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND);
        }

        @Test
        void 차례가_된_사용자는_토큰을_받고_waiting에서_빠진다() {
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 5);
            stringRedisTemplate.opsForValue().set(ADMITTED, "10");

            IssueTokenOutcome o = issue(42);

            assertEquals(IssueTokenOutcome.Type.TOKEN_ISSUED, o.type());
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

            assertEquals(IssueTokenOutcome.Type.TOKEN_ISSUED, second.type());
            assertEquals(first.issuedAt(), second.issuedAt());
        }

        @Test
        void 차례_전이면_NOT_READY이고_issued에_쓰지_않는다() {
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 15);
            stringRedisTemplate.opsForValue().set(ADMITTED, "10");

            IssueTokenOutcome o = issue(42);

            assertEquals(IssueTokenOutcome.Type.NOT_READY, o.type());
            assertNull(stringRedisTemplate.opsForZSet().score(ISSUED, "42"));
            assertNotNull(stringRedisTemplate.opsForZSet().score(WAITING, "42"));
        }

        @Test
        void admitted가_없으면_NOT_READY() {
            stringRedisTemplate.opsForZSet().add(WAITING, "42", 1);

            assertEquals(IssueTokenOutcome.Type.NOT_READY, issue(42).type());
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

            assertEquals(IssueTokenOutcome.Type.TOKEN_ISSUED, o.type());
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
        void 다른_회차의_줄은_보지_않는다() {
            stringRedisTemplate.opsForZSet().add("queue:{" + OTHER_SCHEDULE_ID + "}:waiting", "42", 1);
            stringRedisTemplate.opsForValue().set("queue:{" + OTHER_SCHEDULE_ID + "}:admitted", "10");

            assertEquals(IssueTokenOutcome.Type.NOT_JOINED, issue(42).type());
        }
    }
}
