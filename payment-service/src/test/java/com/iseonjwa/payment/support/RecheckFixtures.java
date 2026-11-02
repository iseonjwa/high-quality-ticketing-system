package com.iseonjwa.payment.support;

import com.iseonjwa.payment.payment.domain.Payment;
import com.iseonjwa.payment.payment.domain.PaymentMethod;
import com.iseonjwa.payment.payment.domain.PaymentRepository;
import com.iseonjwa.payment.payment.domain.PaymentStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 재확인 테스트용 결제 행을 만들고 지운다. 재확인은 전역 후보를 집으므로 테스트는 트랜잭션 없이 커밋된 행으로 돌리고,
 * 끝나면 만든 행과 딸린 이력·Outbox를 지운다. 다른 테스트가 남긴 재확인 대상은 미래로 미뤄 둔다.
 */
public final class RecheckFixtures {
    public static final BigDecimal AMOUNT = BigDecimal.valueOf(50000);
    private static final AtomicLong RESERVATION_IDS = new AtomicLong(7_000_000);

    private final PaymentRepository payments;
    private final JdbcTemplate jdbc;
    private final List<Payment> created = new ArrayList<>();

    public RecheckFixtures(PaymentRepository payments, JdbcTemplate jdbc) {
        this.payments = payments;
        this.jdbc = jdbc;
    }

    /** 다른 테스트가 커밋해 둔 재확인 대상을 하루 뒤로 미룬다. 이 테스트의 후보만 남기려는 것이다. */
    public void parkOtherDuePayments() {
        jdbc.update("UPDATE payments SET next_check_at = now() + interval '1 day' "
                + "WHERE status IN ('REQUESTED', 'UNKNOWN') AND next_check_at <= now()");
    }

    /** 재확인 차례가 지난 결제를 만든다. {@code retryCount}는 이미 PG를 부른 횟수다. */
    public Payment due(PaymentStatus status, int retryCount) {
        return create(status, retryCount, Instant.now().minusSeconds(5));
    }

    public Payment create(PaymentStatus status, int retryCount, Instant nextCheckAt) {
        var reservationId = RESERVATION_IDS.incrementAndGet();
        var saved = payments.save(Payment.request("PAY-R" + reservationId, reservationId, 1L, AMOUNT,
                PaymentMethod.CARD, UUID.randomUUID(), Instant.now()));
        jdbc.update("UPDATE payments SET status = ?, unknown_retry_count = ?, next_check_at = ? WHERE id = ?",
                status.name(), retryCount, java.sql.Timestamp.from(nextCheckAt), saved.getId());
        created.add(saved);
        return payments.findById(saved.getId()).orElseThrow();
    }

    public Payment reload(Payment payment) {
        return payments.findById(payment.getId()).orElseThrow();
    }

    public int outboxCount(Payment payment) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE message_key = ?", Integer.class,
                String.valueOf(payment.getReservationId()));
    }

    public String outboxEventType(Payment payment) {
        return jdbc.queryForObject("SELECT event_type FROM outbox_events WHERE message_key = ?", String.class,
                String.valueOf(payment.getReservationId()));
    }

    public int historyCount(Payment payment) {
        return jdbc.queryForObject("SELECT count(*) FROM payment_status_histories WHERE payment_id = ?",
                Integer.class, payment.getId());
    }

    public String lastHistoryReason(Payment payment) {
        return jdbc.queryForObject("SELECT reason FROM payment_status_histories WHERE payment_id = ? ORDER BY id DESC LIMIT 1",
                String.class, payment.getId());
    }

    public void cleanUp() {
        for (var payment : created) {
            jdbc.update("DELETE FROM outbox_events WHERE message_key = ?", String.valueOf(payment.getReservationId()));
            jdbc.update("DELETE FROM payment_status_histories WHERE payment_id = ?", payment.getId());
            jdbc.update("DELETE FROM payments WHERE id = ?", payment.getId());
        }
        created.clear();
    }
}
