package com.iseonjwa.payment.payment.domain;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.iseonjwa.payment.payment.domain.PaymentStatus.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentStatusTest {
    @Test
    void 설계의_허용_전이만_통과한다() {
        assertTrue(REQUESTED.canTransitionTo(APPROVED));
        assertTrue(REQUESTED.canTransitionTo(DECLINED));
        assertTrue(REQUESTED.canTransitionTo(UNKNOWN));
        assertTrue(REQUESTED.canTransitionTo(NEEDS_MANUAL_CHECK));
        assertTrue(UNKNOWN.canTransitionTo(APPROVED));
        assertTrue(NEEDS_MANUAL_CHECK.canTransitionTo(REFUNDED));
        assertTrue(APPROVED.canTransitionTo(REFUNDED));
    }

    @Test
    void 종결_상태에서는_어디로도_갈_수_없다() {
        for (var next : PaymentStatus.values()) {
            assertFalse(DECLINED.canTransitionTo(next), "DECLINED -> " + next);
            assertFalse(REFUNDED.canTransitionTo(next), "REFUNDED -> " + next);
        }
    }

    @Test
    void 확정된_승인을_거절로_되돌릴_수_없다() {
        assertFalse(APPROVED.canTransitionTo(DECLINED));
        assertFalse(APPROVED.canTransitionTo(UNKNOWN));
    }

    @Test
    void UNKNOWN에서_REQUESTED로_되돌아가지_않는다() {
        assertFalse(UNKNOWN.canTransitionTo(REQUESTED));
        assertFalse(UNKNOWN.canTransitionTo(UNKNOWN));
    }

    @Test
    void 허용되지_않는_전이는_예외로_알린다() {
        var exception = assertThrows(IllegalStateException.class, () -> DECLINED.requireTransitionTo(APPROVED));
        assertEquals("허용되지 않는 결제 상태 전이입니다: DECLINED -> APPROVED", exception.getMessage());
    }

    @Test
    void 재확인_대상은_REQUESTED와_UNKNOWN뿐이다() {
        for (var status : PaymentStatus.values()) {
            assertEquals(Set.of(REQUESTED, UNKNOWN).contains(status), status.awaitsRecheck(), status.name());
        }
    }
}
