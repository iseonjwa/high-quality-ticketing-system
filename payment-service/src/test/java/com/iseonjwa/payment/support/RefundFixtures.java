package com.iseonjwa.payment.support;

import com.iseonjwa.payment.support.AppendOnlyCleanup;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 환불 소비 테스트용 결제 행을 커밋해 만들고 지운다. 이 범위의 예약 ID는 환불 소비 테스트만 쓴다.
 */
public final class RefundFixtures {
    public static final long USER_ID = 77L;
    public static final long AMOUNT = 50000L;
    private static final long FIRST_RESERVATION = 8_800_000;
    private static final long LAST_RESERVATION = 8_899_999;
    private static final AtomicLong RESERVATION_IDS = new AtomicLong(FIRST_RESERVATION);

    private final JdbcTemplate jdbc;

    public RefundFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 결제를 만들고 (paymentId, reservationId)를 돌려준다. */
    public CreatedPayment payment(String status) {
        var reservationId = RESERVATION_IDS.incrementAndGet();
        var extra = switch (status) {
            case "APPROVED" -> "pg_tx_id, approved_at, pg_approved_amount";
            case "DECLINED" -> "decline_code";
            default -> "next_check_at";
        };
        var values = switch (status) {
            case "APPROVED" -> "'pg-tx-" + reservationId + "', now(), " + AMOUNT;
            case "DECLINED" -> "'OTHER'";
            // 재확인 스케줄러가 집는 대상이 되지 않게 먼 미래로 둔다
            default -> "now() + interval '1 year'";
        };
        jdbc.update("""
                INSERT INTO payments (payment_no, reservation_id, user_id, amount, method, status, pg_order_id, pg_provider,
                                     created_at, updated_at, %s)
                VALUES (?, ?, ?, %d, 'CARD', '%s', ?, 'MOCK', now(), now(), %s)""".formatted(extra, AMOUNT, status, values),
                "PAY-F" + reservationId, reservationId, USER_ID, UUID.randomUUID());
        var id = jdbc.queryForObject("SELECT id FROM payments WHERE reservation_id = ?", Long.class, reservationId);
        return new CreatedPayment(id, reservationId);
    }

    public record CreatedPayment(long paymentId, long reservationId) {}

    public int refundCount(long paymentId) {
        return jdbc.queryForObject("SELECT count(*) FROM refunds WHERE payment_id = ?", Integer.class, paymentId);
    }

    public int refundHistoryCount(long paymentId) {
        return jdbc.queryForObject("SELECT count(*) FROM payment_status_histories WHERE payment_id = ? AND target_type = 'REFUND'",
                Integer.class, paymentId);
    }

    public int processedCount(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, eventId);
    }

    /** 이 범위의 결제와 딸린 환불·이력을 지운다. 처리 이력은 테스트가 쓴 eventId로 지운다. 테스트 시작 전과 끝에 부른다. */
    public void cleanUp(Iterable<UUID> eventIds) {
        for (var eventId : eventIds) {
            jdbc.update("DELETE FROM processed_events WHERE event_id = ?", eventId);
        }
        jdbc.update("DELETE FROM refunds WHERE payment_id IN (SELECT id FROM payments WHERE reservation_id BETWEEN ? AND ?)",
                FIRST_RESERVATION, LAST_RESERVATION);
        AppendOnlyCleanup.deleteHistories(jdbc, "payment_id IN "
                + "(SELECT id FROM payments WHERE reservation_id BETWEEN ? AND ?)", FIRST_RESERVATION, LAST_RESERVATION);
        jdbc.update("DELETE FROM payments WHERE reservation_id BETWEEN ? AND ?", FIRST_RESERVATION, LAST_RESERVATION);
    }
}
