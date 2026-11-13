package com.iseonjwa.reservation.concert.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedLoadCacheTest {

    private final SharedLoadCache<Long, String> cache = new SharedLoadCache<>(null, value -> !value.isEmpty());

    @Test
    void 같은_키를_동시에_처음_읽어도_한_번만_읽고_모두_같은_값을_받는다() throws Exception {
        int requests = 50;
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                results.add(pool.submit(() -> cache.get(1L, () -> {
                    loads.incrementAndGet();
                    loading.countDown();
                    await(release);
                    return "layout";
                })));
            }
            // 첫 읽기가 끝나지 않은 동안 나머지 요청이 모두 들어오게 잠시 붙잡아 둔다
            assertTrue(loading.await(5, TimeUnit.SECONDS));
            Thread.sleep(200);
            release.countDown();

            for (Future<String> result : results) {
                assertEquals("layout", result.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, loads.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 읽다_실패하면_남기지_않고_다음_요청이_다시_읽는다() {
        assertThrows(IllegalStateException.class, () -> cache.get(1L, () -> {
            throw new IllegalStateException("DB 오류");
        }));

        assertEquals("layout", cache.get(1L, () -> "layout"));
    }

    @Test
    void 남기지_않는_값은_다음_요청이_다시_읽는다() {
        AtomicInteger loads = new AtomicInteger();

        cache.get(1L, () -> {
            loads.incrementAndGet();
            return "";
        });
        cache.get(1L, () -> {
            loads.incrementAndGet();
            return "layout";
        });
        cache.get(1L, () -> {
            loads.incrementAndGet();
            return "other";
        });

        assertEquals(2, loads.get());
        assertEquals("layout", cache.get(1L, () -> "other"));
    }

    // P4c(1초 캐시)가 쓰는 만료
    @Test
    void 만료_시간이_지나면_다시_읽는다() throws Exception {
        SharedLoadCache<Long, String> ttlCache = new SharedLoadCache<>(Duration.ofMillis(200), value -> true);
        AtomicInteger loads = new AtomicInteger();

        ttlCache.get(1L, () -> "v" + loads.incrementAndGet());
        assertEquals("v1", ttlCache.get(1L, () -> "v" + loads.incrementAndGet()));
        Thread.sleep(300);

        assertEquals("v2", ttlCache.get(1L, () -> "v" + loads.incrementAndGet()));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
