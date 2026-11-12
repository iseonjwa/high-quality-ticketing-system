package com.iseonjwa.payment.pg.infrastructure;

import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgPermit;
import com.iseonjwa.payment.pg.application.PgUnavailableException;
import com.iseonjwa.payment.pg.application.UnknownCause;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// 일반 결제(pg-approve)와 자동결제(pg-billing)가 자리와 차단기를 나누는지 확인한다(ADR 0051, ISE-236)
class PgGroupSeparationTest {
    private Resilience4jPgApprovalGate approval;
    private Resilience4jPgBillingGate billing;

    @BeforeEach
    void setUp() {
        var configuration = new PgProtectionConfiguration();
        var breakers = configuration.pgCircuitBreakerRegistry();
        var bulkheads = configuration.pgBulkheadRegistry();
        // 동시 허가는 기본값(승인 10, 자동결제 3). CB는 최근 4회 중 50% 이상 실패하면 열리게 줄인다
        var small = new PgProtectionProperties.Group(null, 4, 4, 50f, Duration.ofSeconds(10), 3, null);
        var properties = new PgProtectionProperties(small, null, small);
        approval = configuration.pgApprovalGate(properties, breakers, bulkheads);
        billing = configuration.pgBillingGate(properties, breakers, bulkheads);
    }

    @Test
    void 자동결제_그룹의_기본_동시_허가는_3이고_넘으면_즉시_거절한다() {
        var held = new ArrayList<PgPermit>();
        for (var i = 0; i < 3; i++) held.add(billing.acquire());

        assertThrows(PgUnavailableException.class, billing::acquire);

        held.forEach(PgPermit::release);
        billing.acquire().release();
    }

    @Test
    void 한쪽이_가득_차도_다른_쪽은_허가한다() {
        var billingHeld = new ArrayList<PgPermit>();
        for (var i = 0; i < 3; i++) billingHeld.add(billing.acquire());
        approval.acquire().release();
        billingHeld.forEach(PgPermit::release);

        var approvalHeld = new ArrayList<PgPermit>();
        for (var i = 0; i < 10; i++) approvalHeld.add(approval.acquire());
        billing.acquire().release();
        approvalHeld.forEach(PgPermit::release);
    }

    @Test
    void 차단기_상태도_그룹별이다() {
        for (var i = 0; i < 4; i++) {
            var permit = billing.acquire();
            permit.callStarted();
            permit.settle(new PgClient.Unknown(UnknownCause.TIMEOUT));
        }

        assertEquals(CircuitBreaker.State.OPEN, billing.circuitBreaker().getState());
        assertEquals(CircuitBreaker.State.CLOSED, approval.circuitBreaker().getState());
        assertThrows(PgUnavailableException.class, billing::acquire);
        approval.acquire().release();
    }

    @Test
    void 그룹_이름은_자동결제가_pg_billing이다() {
        assertEquals("pg-billing", billing.bulkhead().getName());
        assertEquals("pg-billing", billing.circuitBreaker().getName());
        assertEquals(3, billing.bulkhead().getBulkheadConfig().getMaxConcurrentCalls());
    }
}
