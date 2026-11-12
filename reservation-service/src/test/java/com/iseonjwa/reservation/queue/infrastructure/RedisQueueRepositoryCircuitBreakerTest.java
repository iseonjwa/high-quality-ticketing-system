package com.iseonjwa.reservation.queue.infrastructure;

import com.iseonjwa.reservation.queue.domain.PositionOutcome;
import com.iseonjwa.reservation.queue.domain.QueueStorageUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 대기열 Redis 차단기가 Redis 장애에만 열리고, 열린 동안 Redis를 부르지 않고 열림 시간을 넘기는지 Redis 없이 확인 (ISE-59)
// Note: 운영과 같은 규칙(circuitBreakerConfig)에 최소 호출 수·시험 호출 수만 2로 줄인다
class RedisQueueRepositoryCircuitBreakerTest {

    static final Duration OPEN = Duration.ofSeconds(5);
    static final QueueRedisCircuitBreakerProperties PROPERTIES =
            new QueueRedisCircuitBreakerProperties(10, 2, 50, Duration.ofMillis(50), 80, OPEN, 2);

    final StringRedisTemplate template = mock(StringRedisTemplate.class);
    final CircuitBreaker circuitBreaker =
            CircuitBreaker.of("test", QueueRedisCircuitBreakerConfiguration.circuitBreakerConfig(PROPERTIES));
    final RedisQueueRepository repository = new RedisQueueRepository(template, circuitBreaker, PROPERTIES);

    @Test
    void 시한_초과가_쌓이면_열리고_그_뒤로는_Redis를_부르지_않는다() {
        failWith(new QueryTimeoutException("Redis command timed out"));

        for (int i = 0; i < 2; i++) {
            var e = assertThrows(QueueStorageUnavailableException.class, this::position);
            assertEquals(Optional.empty(), e.retryAfter()); // 차단기가 거절한 게 아니라서 열림 시간을 모른다
        }
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());

        var e = assertThrows(QueueStorageUnavailableException.class, this::position);
        assertInstanceOf(CallNotPermittedException.class, e.getCause());
        assertEquals(Optional.of(OPEN), e.retryAfter());
        verifyRedisCalled(2); // 세 번째는 Redis를 부르지 않았다
    }

    @Test
    void 연결_실패로도_열린다() {
        failWith(new RedisConnectionFailureException("Unable to connect to Redis"));

        for (int i = 0; i < 2; i++) {
            assertThrows(QueueStorageUnavailableException.class, this::position);
        }

        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
    }

    @Test
    void 버그_같은_다른_예외로는_열리지_않는다() {
        failWith(new IllegalStateException("bug"));

        for (int i = 0; i < 5; i++) {
            assertThrows(IllegalStateException.class, this::position);
        }

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        verifyRedisCalled(5);
    }

    @Test
    void 닫혀_있으면_결과를_그대로_돌려준다() {
        succeedWith(List.of("IN_QUEUE", 7L, 3L));

        assertEquals(new PositionOutcome(PositionOutcome.Type.IN_QUEUE, 7L, 3L), position());
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
    }

    @Test
    void 시험_호출이_성공하면_다시_닫힌다() {
        circuitBreaker.transitionToOpenState();
        circuitBreaker.transitionToHalfOpenState(); // 열림 시간(5초)을 기다리는 대신 바로 시험 상태로 넘긴다
        succeedWith(List.of("NOT_JOINED"));

        for (int i = 0; i < 2; i++) {
            position();
        }

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
    }

    @Test
    void 시험_호출이_실패하면_다시_열린다() {
        circuitBreaker.transitionToOpenState();
        circuitBreaker.transitionToHalfOpenState();
        failWith(new QueryTimeoutException("Redis command timed out"));

        for (int i = 0; i < 2; i++) {
            assertThrows(QueueStorageUnavailableException.class, this::position);
        }

        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
    }


    private PositionOutcome position() {
        return repository.position(1L, 42L, 600_000L);
    }

    // execute(스크립트, 키 목록, 가변 인자)를 인자 개수와 상관없이 실패시킨다
    @SuppressWarnings("unchecked")
    private void failWith(RuntimeException e) {
        when(template.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenThrow(e);
    }

    @SuppressWarnings("unchecked")
    private void succeedWith(List<Object> result) {
        when(template.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(result);
    }

    @SuppressWarnings("unchecked")
    private void verifyRedisCalled(int count) {
        verify(template, times(count)).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }
}
