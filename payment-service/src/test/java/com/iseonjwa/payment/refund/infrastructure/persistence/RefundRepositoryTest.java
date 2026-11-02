package com.iseonjwa.payment.refund.infrastructure.persistence;

import com.iseonjwa.payment.refund.domain.RefundReason;
import com.iseonjwa.payment.refund.domain.RefundRepository;
import com.iseonjwa.payment.refund.domain.RefundSource;
import com.iseonjwa.payment.refund.domain.RefundStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Note: 결제 행은 테스트 트랜잭션 안에서 만들고 롤백한다. 원자적 생성 쿼리도 같은 트랜잭션이라 그 행을 본다.
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
@Transactional
class RefundRepositoryTest {
    private static final long RESERVATION_ID = 8_900_001L;
    private static final BigDecimal PG_APPROVED_AMOUNT = BigDecimal.valueOf(50000);

    @Autowired
    RefundRepository refunds;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 승인된_결제면_환불_한_건을_만들고_금액은_결제의_승인_금액을_복사한다() {
        var paymentId = payment(RESERVATION_ID, "APPROVED");
        var eventId = UUID.randomUUID();
        var pgRefundId = UUID.randomUUID();
        var now = Instant.now();

        var created = refunds.createForApprovedPayment(paymentId, RESERVATION_ID, pgRefundId, RefundReason.CANCELED,
                RefundSource.EVENT, eventId, now);

        assertEquals(1, created);
        var refund = refunds.findByPaymentId(paymentId).orElseThrow();
        assertEquals(0, PG_APPROVED_AMOUNT.compareTo(refund.getAmount()));
        assertEquals(RefundStatus.REQUESTED, refund.getStatus());
        assertEquals(RefundReason.CANCELED, refund.getReason());
        assertEquals(RefundSource.EVENT, refund.getSource());
        assertEquals(eventId, refund.getSourceEventId());
        assertEquals(pgRefundId, refund.getPgRefundId());
        assertEquals(0, refund.getAttempt());
        assertEquals(now.toEpochMilli(), refund.getNextCheckAt().toEpochMilli());
        assertEquals(null, refund.getPgRequestedAt());
    }

    @Test
    void 예매_ID가_다르면_만들지_않는다() {
        var paymentId = payment(RESERVATION_ID + 1, "APPROVED");

        assertEquals(0, create(paymentId, RESERVATION_ID + 999));
        assertTrue(refunds.findByPaymentId(paymentId).isEmpty());
    }

    @Test
    void 승인되지_않은_결제면_만들지_않는다() {
        var requested = payment(RESERVATION_ID + 2, "REQUESTED");
        var declined = payment(RESERVATION_ID + 3, "DECLINED");

        assertEquals(0, create(requested, RESERVATION_ID + 2));
        assertEquals(0, create(declined, RESERVATION_ID + 3));
        assertTrue(refunds.findByPaymentId(requested).isEmpty());
        assertTrue(refunds.findByPaymentId(declined).isEmpty());
    }

    @Test
    void 두_번째_생성은_충돌로_0행이고_첫_환불을_바꾸지_않는다() {
        var paymentId = payment(RESERVATION_ID + 4, "APPROVED");
        var first = UUID.randomUUID();

        assertEquals(1, refunds.createForApprovedPayment(paymentId, RESERVATION_ID + 4, first, RefundReason.CANCELED,
                RefundSource.EVENT, UUID.randomUUID(), Instant.now()));
        assertEquals(0, refunds.createForApprovedPayment(paymentId, RESERVATION_ID + 4, UUID.randomUUID(),
                RefundReason.NOT_CONFIRMABLE, RefundSource.DLQ, UUID.randomUUID(), Instant.now()));

        var refund = refunds.findByPaymentId(paymentId).orElseThrow();
        assertEquals(first, refund.getPgRefundId());
        assertEquals(RefundReason.CANCELED, refund.getReason());
    }

    private int create(long paymentId, long reservationId) {
        return refunds.createForApprovedPayment(paymentId, reservationId, UUID.randomUUID(), RefundReason.CANCELED,
                RefundSource.EVENT, UUID.randomUUID(), Instant.now());
    }

    private long payment(long reservationId, String status) {
        var extra = switch (status) {
            case "APPROVED" -> "pg_tx_id, approved_at, pg_approved_amount";
            case "DECLINED" -> "decline_code";
            default -> "next_check_at";
        };
        var values = switch (status) {
            case "APPROVED" -> "'pg-tx-" + reservationId + "', now(), 50000";
            case "DECLINED" -> "'OTHER'";
            default -> "now() + interval '10 seconds'";
        };
        jdbc.update("""
                INSERT INTO payments (payment_no, reservation_id, user_id, amount, method, status, pg_order_id,
                                     created_at, updated_at, %s)
                VALUES (?, ?, 1, 50000, 'CARD', '%s', ?, now(), now(), %s)""".formatted(extra, status, values),
                "PAY-" + reservationId, reservationId, UUID.randomUUID());
        return jdbc.queryForObject("SELECT id FROM payments WHERE reservation_id = ?", Long.class, reservationId);
    }
}
