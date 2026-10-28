package com.iseonjwa.payment.payment.application;

import com.iseonjwa.payment.payment.domain.DeclineReason;
import com.iseonjwa.payment.payment.domain.Payment;
import com.iseonjwa.payment.payment.domain.PaymentMethod;
import com.iseonjwa.payment.payment.domain.PaymentRepository;
import com.iseonjwa.payment.payment.domain.PaymentStatus;
import com.iseonjwa.payment.payment.domain.PaymentStatusHistoryRepository;
import com.iseonjwa.payment.payment.domain.StatusActor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
@Transactional
class PaymentTransitionServiceTest {
    private static final BigDecimal AMOUNT = BigDecimal.valueOf(50000);
    private static final AtomicLong RESERVATION_IDS = new AtomicLong(2_000_000);

    @Autowired
    PaymentTransitionService transitions;
    @Autowired
    PaymentRepository payments;
    @Autowired
    PaymentStatusHistoryRepository histories;

    @Test
    void 승인_전이는_상태와_PG_정보를_바꾸고_이력을_남긴다() {
        var payment = requested();

        assertTrue(transitions.approve(payment.getId(), PaymentStatus.REQUESTED, "pg-tx-approve", AMOUNT,
                StatusActor.SYSTEM));

        var approved = payments.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.APPROVED, approved.getStatus());
        assertEquals("pg-tx-approve", approved.getPgTxId());
        assertEquals(0, AMOUNT.compareTo(approved.getPgApprovedAmount()));
        assertNotNull(approved.getApprovedAt());
        assertNull(approved.getNextCheckAt(), "종결 상태는 재확인 대상이 아니다");
        var history = histories.findByPaymentId(payment.getId());
        assertEquals(1, history.size());
        assertEquals(PaymentStatus.REQUESTED, history.get(0).getFromStatus());
        assertEquals(PaymentStatus.APPROVED, history.get(0).getToStatus());
        assertEquals("SYSTEM", history.get(0).getActor());
    }

    @Test
    void 이미_바뀐_결제를_같은_상태_기준으로_다시_전이하면_이력을_쓰지_않는다() {
        var payment = requested();
        assertTrue(transitions.approve(payment.getId(), PaymentStatus.REQUESTED, "pg-tx-first", AMOUNT,
                StatusActor.SYSTEM));

        assertFalse(transitions.decline(payment.getId(), PaymentStatus.REQUESTED, DeclineReason.OTHER,
                StatusActor.SYSTEM));

        assertEquals(PaymentStatus.APPROVED, payments.findById(payment.getId()).orElseThrow().getStatus());
        assertEquals(1, histories.findByPaymentId(payment.getId()).size(), "영향 행이 0이면 이력도 없다(INV-5)");
    }

    @Test
    void 거절은_사유를_저장한다() {
        var payment = requested();

        assertTrue(transitions.decline(payment.getId(), PaymentStatus.REQUESTED, DeclineReason.TOKEN_EXPIRED,
                StatusActor.SYSTEM));

        var declined = payments.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.DECLINED, declined.getStatus());
        assertEquals(DeclineReason.TOKEN_EXPIRED, declined.getDeclineCode());
        assertEquals("TOKEN_EXPIRED", histories.findByPaymentId(payment.getId()).get(0).getReason());
    }

    @Test
    void 불확실한_응답은_UNKNOWN으로_두고_다음_조회_시각을_남긴다() {
        var payment = requested();
        var nextCheckAt = Instant.parse("2026-10-28T03:00:30Z");

        assertTrue(transitions.markUnknown(payment.getId(), PaymentStatus.REQUESTED, nextCheckAt, "TIMEOUT",
                StatusActor.SYSTEM));

        var unknown = payments.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.UNKNOWN, unknown.getStatus());
        assertEquals(nextCheckAt, unknown.getNextCheckAt());
    }

    @Test
    void 금액_불일치는_확인_필요로_두고_PG_정보를_보존한다() {
        var payment = requested();

        assertTrue(transitions.markNeedsManualCheck(payment.getId(), PaymentStatus.REQUESTED, "pg-tx-mismatch",
                BigDecimal.valueOf(49000), "PG_AMOUNT_MISMATCH", StatusActor.SYSTEM));

        var checked = payments.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.NEEDS_MANUAL_CHECK, checked.getStatus());
        assertEquals("pg-tx-mismatch", checked.getPgTxId());
        assertEquals(0, BigDecimal.valueOf(49000).compareTo(checked.getPgApprovedAmount()));
        assertNotNull(checked.getApprovedAt());
    }

    @Test
    void 재조회_소진은_이전에_저장한_PG_정보를_지우지_않는다() {
        var payment = requested();
        assertTrue(transitions.markUnknown(payment.getId(), PaymentStatus.REQUESTED, Instant.now().plusSeconds(30),
                "TIMEOUT", StatusActor.SYSTEM));

        assertTrue(transitions.markNeedsManualCheck(payment.getId(), PaymentStatus.UNKNOWN, null, null,
                "PG_UNRESOLVED", StatusActor.SYSTEM));

        var checked = payments.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.NEEDS_MANUAL_CHECK, checked.getStatus());
        assertNull(checked.getPgTxId());
        assertNull(checked.getApprovedAt());
    }

    @Test
    void 허용되지_않는_전이는_저장소를_부르기_전에_거절한다() {
        var payment = requested();
        assertTrue(transitions.decline(payment.getId(), PaymentStatus.REQUESTED, DeclineReason.OTHER,
                StatusActor.SYSTEM));

        assertThrows(IllegalStateException.class, () -> transitions.approve(payment.getId(), PaymentStatus.DECLINED,
                "pg-tx-late", AMOUNT, StatusActor.SYSTEM));
    }

    @Test
    void 결제_금액과_다른_승인_금액은_DB_제약이_막는다() {
        var payment = requested();

        assertThrows(DataIntegrityViolationException.class, () -> transitions.approve(payment.getId(),
                PaymentStatus.REQUESTED, "pg-tx-wrong", BigDecimal.valueOf(49000), StatusActor.SYSTEM));
    }

    @Test
    void 운영자_전이는_이력의_행위자에_OPS를_남긴다() {
        var payment = requested();
        assertTrue(transitions.markNeedsManualCheck(payment.getId(), PaymentStatus.REQUESTED, null, null,
                "PG_UNRESOLVED", StatusActor.SYSTEM));

        assertTrue(transitions.approve(payment.getId(), PaymentStatus.NEEDS_MANUAL_CHECK, "pg-tx-ops", AMOUNT,
                StatusActor.ops("kim")));

        var history = histories.findByPaymentId(payment.getId());
        assertEquals("OPS:kim", history.get(1).getActor());
    }

    private Payment requested() {
        var reservationId = RESERVATION_IDS.incrementAndGet();
        return payments.save(Payment.request("PAY-" + reservationId, reservationId, 1L, AMOUNT, PaymentMethod.CARD,
                UUID.randomUUID(), Instant.now()));
    }
}
