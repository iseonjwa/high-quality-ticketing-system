package com.iseonjwa.reservation.queue.infrastructure;

import com.iseonjwa.reservation.queue.domain.EnterOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.context.annotation.Import;
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
@Import(RedisQueueRepository.class) // 슬라이스 테스트는 @Repository를 자동으로 안 띄워서 직접 넣어야 함
class RedisQueueRepositoryTest {

    static final long SCHEDULE_ID = 31L;
    static final long OTHER_SCHEDULE_ID = 32L;
    static final long TOKEN_TTL_MILLIS = 600_000; // 10분
    static final long KEY_EXPIRE_AT_EPOCH_SECOND = Instant.now().plus(1, DAYS).getEpochSecond();

    static final String SEQ = "queue:{" + SCHEDULE_ID + "}:seq";
    static final String WAITING = "queue:{" + SCHEDULE_ID + "}:waiting";
    static final String ISSUED = "queue:{" + SCHEDULE_ID + "}:issued";

    @Autowired RedisQueueRepository queueRepository;
    @Autowired StringRedisTemplate stringRedisTemplate;


    @BeforeEach
    void setUp() {
        stringRedisTemplate.delete(QueueKeys.of(SCHEDULE_ID).enterKeys());
        stringRedisTemplate.delete(QueueKeys.of(OTHER_SCHEDULE_ID).enterKeys());
    }


    @Test
    void 처음_진입하면_새_번호를_받는다() {
        EnterOutcome o = queueRepository.enter(SCHEDULE_ID, 42, 0, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND);
        assertEquals(Type.JOINED, o.type());
        assertEquals(1L, o.seq());
    }

    @Test
    void 다시_진입하면_같은_번호다() {
        queueRepository.enter(SCHEDULE_ID, 42, 0, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND);
        EnterOutcome o = queueRepository.enter(SCHEDULE_ID, 42, 0, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND);
        assertEquals(Type.ALREADY_WAITING, o.type());
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
                    return queueRepository.enter(SCHEDULE_ID, 42, 0, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND);
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
            tasks.add(() -> queueRepository.enter(SCHEDULE_ID, u, 0, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND));
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
        EnterOutcome a = queueRepository.enter(SCHEDULE_ID, 42, 0, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND);
        EnterOutcome b = queueRepository.enter(OTHER_SCHEDULE_ID, 42, 0, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND);

        assertEquals(1L, a.seq());
        assertEquals(1L, b.seq());
    }

    @Test
    void 토큰을_받은_사용자는_새_번호_없이_TOKEN_ISSUED() {
        long now = System.currentTimeMillis();
        stringRedisTemplate.opsForZSet().add(ISSUED, "42", now);

        EnterOutcome o = queueRepository.enter(SCHEDULE_ID, 42, 0, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND);

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

        EnterOutcome r = queueRepository.enter(SCHEDULE_ID, 42, 0, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND);

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

        EnterOutcome o = queueRepository.enter(SCHEDULE_ID, 42, 0, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND);

        assertEquals(Type.JOINED, o.type());
        assertEquals(6L, o.seq());
        assertNull(stringRedisTemplate.opsForZSet().score(ISSUED, "42"));
        assertEquals(6.0, stringRedisTemplate.opsForZSet().score(WAITING, "42"));
    }

    @Test
    void 오픈_전이면_SALE_NOT_OPEN() {
        long oneHourLater = System.currentTimeMillis() + Duration.ofHours(1).toMillis();

        EnterOutcome o = queueRepository.enter(SCHEDULE_ID, 42, oneHourLater, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND);

        assertEquals(Type.SALE_NOT_OPEN, o.type());
        assertFalse(stringRedisTemplate.hasKey(SEQ));
        assertFalse(stringRedisTemplate.hasKey(WAITING));
    }

    @Test
    void 새로_받은_seq는_admitted보다_크다() {
        // 100번까지 번호가 나갔고 100번까지 입장시킨 상태 (줄이 빈 상태)
        stringRedisTemplate.opsForValue().set(SEQ, "100");
        stringRedisTemplate.opsForValue().set("queue:{" + SCHEDULE_ID + "}:admitted", "100");

        EnterOutcome o = queueRepository.enter(SCHEDULE_ID, 42, 0, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND);

        assertEquals(Type.JOINED, o.type());
        assertTrue(o.seq() > o.admitted()); // 101 > 100
    }

    @Test
    void 새로_줄_서면_seq와_waiting에_만료가_걸린다() {
        queueRepository.enter(SCHEDULE_ID, 42, 0, TOKEN_TTL_MILLIS, KEY_EXPIRE_AT_EPOCH_SECOND);

        assertTrue(stringRedisTemplate.getExpire(SEQ) > 0); // -1이면 만료가 없는 상태
        assertTrue(stringRedisTemplate.getExpire(WAITING) > 0);
    }
}
