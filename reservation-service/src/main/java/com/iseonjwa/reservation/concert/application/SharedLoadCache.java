package com.iseonjwa.reservation.concert.application;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 키마다 값을 한 번만 읽고, 읽는 동안 들어온 요청은 같은 결과를 기다리게 하는 캐시(P4b·P4c).
 *
 * Note: 오픈 직후처럼 캐시가 빈 순간에 요청이 몰려도 DB 조회는 키마다 하나만 나간다.
 * 그냥 get → 없으면 읽기 → put이면 동시에 온 요청 수만큼 15,000석을 읽어 캐시가 없을 때와 같아진다.
 */
final class SharedLoadCache<K, V> {

    private final ConcurrentHashMap<K, Entry<V>> entries = new ConcurrentHashMap<>();
    private final long ttlNanos;
    private final Predicate<V> keepable;

    // ttl이 null이면 지우지 않는다. keepable이 false인 값(예: 아직 좌석을 만들지 않은 회차의 빈 목록)은 남기지 않는다
    SharedLoadCache(Duration ttl, Predicate<V> keepable) {
        this.ttlNanos = ttl == null ? Long.MAX_VALUE : ttl.toNanos();
        this.keepable = keepable;
    }

    V get(K key, Supplier<V> loader) {
        while (true) {
            Entry<V> current = entries.get(key);
            long now = System.nanoTime();
            if (current != null && !current.expired(now, ttlNanos)) {
                return join(current.value());
            }
            // 읽기 시작 시각을 기준으로 TTL을 센다. 값이 읽힌 시점보다 늦게 끝나지 않게 하려는 것이다
            Entry<V> mine = new Entry<>(new CompletableFuture<>(), now);
            boolean won = current == null ? entries.putIfAbsent(key, mine) == null : entries.replace(key, current, mine);
            if (!won) {
                continue;
            }
            return load(key, mine, loader);
        }
    }

    // 읽기를 일으키지 않고, TTL 안에 정상으로 읽힌 값만 돌려준다. 없거나 읽는 중이거나 낡았으면 null이다
    V peekFresh(K key) {
        Entry<V> current = entries.get(key);
        if (current == null || !current.value().isDone() || current.value().isCompletedExceptionally()
                || current.expired(System.nanoTime(), ttlNanos)) {
            return null;
        }
        return current.value().getNow(null);
    }

    private V load(K key, Entry<V> mine, Supplier<V> loader) {
        try {
            V value = loader.get();
            mine.value().complete(value);
            if (!keepable.test(value)) {
                entries.remove(key, mine);
            }
            return value;
        } catch (RuntimeException e) {
            // 실패는 남기지 않는다. 기다리던 요청은 같은 예외를 받고, 다음 요청이 다시 읽는다
            entries.remove(key, mine);
            mine.value().completeExceptionally(e);
            throw e;
        }
    }

    private static <V> V join(CompletableFuture<V> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    private record Entry<V>(CompletableFuture<V> value, long startedAt) {
        // 읽는 중인 항목은 만료로 보지 않는다. 같이 기다리게 해야 DB 조회가 겹치지 않는다
        boolean expired(long now, long ttlNanos) {
            return value.isDone() && now - startedAt >= ttlNanos;
        }
    }
}
