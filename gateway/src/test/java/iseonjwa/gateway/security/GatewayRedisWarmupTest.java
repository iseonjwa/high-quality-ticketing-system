package iseonjwa.gateway.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.connection.ReactiveRedisConnection;
import org.springframework.data.redis.core.ReactiveRedisCallback;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayRedisWarmupTest {
    @Test
    void PING_응답을_받기_전에는_기동_준비를_끝내지_않는다() throws Exception {
        var pong = Sinks.<String>one();
        var subscribed = new CountDownLatch(1);
        var runner = runner(pong.asMono().doOnSubscribe(subscription -> subscribed.countDown()).flux());

        try (var executor = Executors.newSingleThreadExecutor()) {
            var ready = executor.submit(() -> {
                runner.run(null);
                return true;
            });
            try {
                assertTrue(subscribed.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> ready.get(100, TimeUnit.MILLISECONDS));
            } finally {
                pong.tryEmitValue("PONG");
            }
            assertTrue(ready.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void 일시적인_연결_실패는_다시_확인한_뒤_기동한다() throws Exception {
        var attempts = new AtomicInteger();
        var runner = runner(Flux.defer(() -> attempts.incrementAndGet() == 1
                ? Flux.error(new IllegalStateException("일시적인 연결 실패")) : Flux.just("PONG")));

        runner.run(null);

        assertEquals(2, attempts.get());
    }

    @Test
    void 연결이_계속_실패하면_제한된_재시도_뒤_기동을_실패시킨다() {
        var attempts = new AtomicInteger();
        var runner = runner(Flux.defer(() -> {
            attempts.incrementAndGet();
            return Flux.error(new IllegalStateException("연결 불가"));
        }));

        assertThrows(RuntimeException.class, () -> runner.run(null));
        assertEquals(3, attempts.get());
    }

    @Test
    void PONG_응답이_없으면_준비된_것으로_처리하지_않는다() {
        assertThrows(RuntimeException.class, () -> runner(Flux.empty()).run(null));
    }

    @Test
    @Timeout(10)
    void 응답이_없어도_기동이_무한정_대기하지_않는다() {
        assertThrows(RuntimeException.class, () -> runner(Flux.never()).run(null));
    }

    private ApplicationRunner runner(Flux<String> replies) {
        var connection = mock(ReactiveRedisConnection.class);
        when(connection.ping()).thenReturn(replies.next());
        var redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(org.mockito.ArgumentMatchers.<ReactiveRedisCallback<String>>any()))
                .thenAnswer(invocation -> Flux.defer(() -> Flux.from(
                        invocation.<ReactiveRedisCallback<String>>getArgument(0).doInRedis(connection))));
        return new GatewayConfiguration().redisConnectionWarmup(redis);
    }
}
