package com.iseonjwa.payment.pg.infrastructure;

import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.UnknownCause;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Resilience4jPgGroupTest {
    private static final Duration OPEN_DURATION = Duration.ofMillis(100);

    // 동시 2, 최근 4회 중 50% 이상 실패하면 OPEN, HALF_OPEN 시험 3회
    private static PgProtectionProperties.Group settings(int maxConcurrent, int halfOpenCalls) {
        return new PgProtectionProperties.Group(maxConcurrent, 4, 4, 50f, OPEN_DURATION, halfOpenCalls);
    }

    private static Resilience4jPgGroup group(int maxConcurrent) {
        return new Resilience4jPgGroup("test", settings(maxConcurrent, 3));
    }

    @Test
    void 동시_호출_한도까지만_허가하고_넘으면_기다리지_않고_거절한다() {
        var group = group(2);

        var first = group.tryAcquire();
        var second = group.tryAcquire();
        var third = group.tryAcquire();

        assertTrue(first.isPresent());
        assertTrue(second.isPresent());
        assertTrue(third.isEmpty());
    }

    @Test
    void 정산하거나_반환하면_자리가_돌아온다() {
        var group = group(1);
        var permit = group.tryAcquire().orElseThrow();
        assertTrue(group.tryAcquire().isEmpty());

        permit.success();

        assertEquals(1, group.bulkhead().getMetrics().getAvailableConcurrentCalls());
        assertTrue(group.tryAcquire().isPresent());
    }

    @Test
    void 호출_결과로_세지_않는_반환은_CB를_열지_않는다() {
        var group = group(1);

        for (var i = 0; i < 20; i++) group.tryAcquire().orElseThrow().release();

        assertEquals(CircuitBreaker.State.CLOSED, group.circuitBreaker().getState());
        assertEquals(0, group.circuitBreaker().getMetrics().getNumberOfBufferedCalls());
    }

    @Test
    void 실패가_누적되면_CB가_열려_허가를_거절하고_Bulkhead_자리도_쓰지_않는다() {
        var group = group(2);

        for (var i = 0; i < 4; i++) group.tryAcquire().orElseThrow().failure();

        assertEquals(CircuitBreaker.State.OPEN, group.circuitBreaker().getState());
        assertTrue(group.tryAcquire().isEmpty());
        assertEquals(2, group.bulkhead().getMetrics().getAvailableConcurrentCalls());
    }

    @Test
    void 같은_허가를_두_번_정산해도_한_번만_센다() {
        var group = group(2);
        var permit = group.tryAcquire().orElseThrow();

        permit.success();
        permit.failure();
        permit.close();

        assertEquals(1, group.circuitBreaker().getMetrics().getNumberOfBufferedCalls());
        assertEquals(0, group.circuitBreaker().getMetrics().getNumberOfFailedCalls());
        assertEquals(2, group.bulkhead().getMetrics().getAvailableConcurrentCalls());
    }

    @Test
    void close는_정산하지_않은_허가를_결과로_세지_않고_반환한다() {
        var group = group(1);

        try (var permit = group.tryAcquire().orElseThrow()) {
            assertTrue(group.tryAcquire().isEmpty());
        }

        assertEquals(1, group.bulkhead().getMetrics().getAvailableConcurrentCalls());
        assertEquals(0, group.circuitBreaker().getMetrics().getNumberOfBufferedCalls());
    }

    @Test
    void OPEN이_끝나면_시험_호출이_성공해야_CB가_닫힌다() throws Exception {
        var group = group(5);
        for (var i = 0; i < 4; i++) group.tryAcquire().orElseThrow().failure();
        Thread.sleep(OPEN_DURATION.toMillis() + 50);

        var trials = new ArrayList<com.iseonjwa.payment.pg.application.PgPermit>();
        for (var i = 0; i < 3; i++) trials.add(group.tryAcquire().orElseThrow());
        assertEquals(CircuitBreaker.State.HALF_OPEN, group.circuitBreaker().getState());
        assertTrue(group.tryAcquire().isEmpty(), "시험 호출 수를 넘는 호출은 거절한다");
        trials.forEach(com.iseonjwa.payment.pg.application.PgPermit::success);

        assertEquals(CircuitBreaker.State.CLOSED, group.circuitBreaker().getState());
    }

    @Test
    void 시험_호출을_정산하지_않으면_CB가_닫히지_않는다() throws Exception {
        var group = group(5);
        for (var i = 0; i < 4; i++) group.tryAcquire().orElseThrow().failure();
        Thread.sleep(OPEN_DURATION.toMillis() + 50);
        for (var i = 0; i < 3; i++) group.tryAcquire().orElseThrow();

        // 허가를 쥔 채 정산하지 않은 상태. 이것이 허가 정산을 한 곳에 묶는 이유다(0018)
        assertEquals(CircuitBreaker.State.HALF_OPEN, group.circuitBreaker().getState());
        assertTrue(group.tryAcquire().isEmpty());
    }

    @Test
    void Bulkhead가_거절하면_CB_허가를_돌려줘_시험_호출_자리가_새지_않는다() throws Exception {
        var group = new Resilience4jPgGroup("test", settings(1, 1));
        for (var i = 0; i < 4; i++) group.tryAcquire().orElseThrow().failure();
        Thread.sleep(OPEN_DURATION.toMillis() + 50);
        // Bulkhead를 다른 호출이 이미 채운 상태에서 시험 호출을 시도한다
        assertTrue(group.bulkhead().tryAcquirePermission());

        assertTrue(group.tryAcquire().isEmpty());
        group.bulkhead().onComplete();

        assertTrue(group.tryAcquire().isPresent(), "돌려준 CB 허가로 다시 시험 호출을 할 수 있다");
    }

    @Test
    void 복구_그룹은_결과를_돌려주고_Unknown만_실패로_센다() {
        var gate = new Resilience4jPgRecoveryGate(group(2));

        var found = gate.<PgClient.PaymentLookupResult>execute(PgClient.NotFound::new,
                result -> result instanceof PgClient.Unknown);
        var unknown = gate.<PgClient.PaymentLookupResult>execute(() -> new PgClient.Unknown(UnknownCause.TIMEOUT),
                result -> result instanceof PgClient.Unknown);

        assertTrue(found.isPresent());
        assertTrue(unknown.isPresent());
        assertEquals(2, gate.circuitBreaker().getMetrics().getNumberOfBufferedCalls());
        assertEquals(1, gate.circuitBreaker().getMetrics().getNumberOfFailedCalls());
        assertEquals(2, gate.bulkhead().getMetrics().getAvailableConcurrentCalls());
    }

    @Test
    void 복구_호출이_예외를_던지면_실패로_세고_허가를_반환하고_예외를_다시_던진다() {
        var gate = new Resilience4jPgRecoveryGate(group(2));

        assertThrows(IllegalStateException.class, () -> gate.execute(() -> {
            throw new IllegalStateException("어댑터 오류");
        }, result -> false));

        assertEquals(1, gate.circuitBreaker().getMetrics().getNumberOfFailedCalls());
        assertEquals(2, gate.bulkhead().getMetrics().getAvailableConcurrentCalls());
    }

    @Test
    void 복구_그룹은_허가가_없으면_호출하지_않고_빈_값을_돌려준다() {
        var gate = new Resilience4jPgRecoveryGate(group(1));
        gate.bulkhead().tryAcquirePermission();
        var called = new boolean[1];

        Optional<String> result = gate.execute(() -> {
            called[0] = true;
            return "x";
        }, r -> false);

        assertTrue(result.isEmpty());
        assertFalse(called[0]);
    }

    @Test
    void 신규_승인_그룹이_포화되거나_열려도_복구_그룹은_영향을_받지_않는다() {
        var approve = new Resilience4jPgApprovalGate(group(1));
        var recovery = new Resilience4jPgRecoveryGate(group(2));
        approve.acquire();
        for (var i = 0; i < 4; i++) {
            approve.circuitBreaker().onError(1, java.util.concurrent.TimeUnit.MILLISECONDS, new RuntimeException());
        }

        var result = recovery.execute(() -> "복구 조회", r -> false);

        assertTrue(result.isPresent(), "승인 장애가 복구를 막지 않는다");
    }

    @Test
    void 설정_값이_잘못되면_기동을_막는다() {
        assertThrows(IllegalArgumentException.class, () -> new PgProtectionProperties.Group(0, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new PgProtectionProperties.Group(5, 0, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new PgProtectionProperties.Group(5, 4, 4, 101f, null, null));
    }

    @Test
    void 기본값은_설계의_값이다() {
        var defaults = new PgProtectionProperties(null, null);

        assertEquals(10, defaults.approve().maxConcurrentCalls());
        assertEquals(2, defaults.recovery().maxConcurrentCalls());
        assertEquals(20, defaults.approve().slidingWindowSize());
        assertEquals(50f, defaults.approve().failureRateThreshold());
        assertEquals(Duration.ofSeconds(10), defaults.approve().openDuration());
        assertEquals(3, defaults.approve().halfOpenPermittedCalls());
    }
}
