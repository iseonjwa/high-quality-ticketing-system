package com.iseonjwa.reservation.queue.infrastructure;

import com.iseonjwa.reservation.queue.domain.QueueStorageUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Redis 예외 중 시한 초과·연결 실패만 대기열 저장소 예외로 바뀌는지 Redis 없이 확인 (ISE-59)
class RedisQueueRepositoryFailureTest {

    final StringRedisTemplate template = mock(StringRedisTemplate.class);
    // 예외 변환만 본다. 기본 차단기는 최소 호출 수가 100이라 이 테스트에서는 열리지 않는다
    final RedisQueueRepository repository = new RedisQueueRepository(template, CircuitBreaker.ofDefaults("test"),
            new QueueRedisCircuitBreakerProperties(10, 2, 50, Duration.ofMillis(50), 80, Duration.ofSeconds(5), 2));

    @Test
    void 시한_초과는_대기열_저장소_예외로_바뀌고_원인이_남는다() {
        failWith(new QueryTimeoutException("Redis command timed out"));

        var e = assertThrows(QueueStorageUnavailableException.class,
                () -> repository.position(1L, 42L, 600_000L));
        assertInstanceOf(QueryTimeoutException.class, e.getCause());
    }

    @Test
    void 연결_실패도_대기열_저장소_예외로_바뀐다() {
        failWith(new RedisConnectionFailureException("Unable to connect to Redis"));

        var e = assertThrows(QueueStorageUnavailableException.class,
                () -> repository.position(1L, 42L, 600_000L));
        assertInstanceOf(RedisConnectionFailureException.class, e.getCause());
    }

    @Test
    void 그_밖의_예외는_그대로_나간다() {
        failWith(new IllegalStateException("bug"));

        assertThrows(IllegalStateException.class, () -> repository.position(1L, 42L, 600_000L));
    }

    @Test
    void 네_메서드_모두_변환한다() {
        failWith(new QueryTimeoutException("Redis command timed out"));

        assertThrows(QueueStorageUnavailableException.class,
                () -> repository.enter(1L, 42L, 0L, 0L, 600_000L, 0L));
        assertThrows(QueueStorageUnavailableException.class,
                () -> repository.position(1L, 42L, 600_000L));
        assertThrows(QueueStorageUnavailableException.class,
                () -> repository.admit(1L, 50, 600_000L, 500, 0L));
        assertThrows(QueueStorageUnavailableException.class,
                () -> repository.issueToken(1L, 42L, 600_000L, 0L));
        assertThrows(QueueStorageUnavailableException.class,
                () -> repository.discardToken(1L, 42L, 1_000L));
        assertThrows(QueueStorageUnavailableException.class,
                () -> repository.stats(1L, 600_000L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 발급_시각_읽기도_변환한다() {
        ZSetOperations<String, String> zSet = mock(ZSetOperations.class);
        when(template.opsForZSet()).thenReturn(zSet);
        when(zSet.score(anyString(), anyString())).thenThrow(new QueryTimeoutException("Redis command timed out"));

        var e = assertThrows(QueueStorageUnavailableException.class, () -> repository.issuedAt(1L, 42L));
        assertInstanceOf(QueryTimeoutException.class, e.getCause());
    }

    // execute(스크립트, 키 목록, 가변 인자)를 인자 개수와 상관없이 실패시킨다
    @SuppressWarnings("unchecked")
    private void failWith(RuntimeException e) {
        when(template.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenThrow(e);
    }
}
