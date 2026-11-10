package com.iseonjwa.payment.support;

import com.iseonjwa.payment.payment.domain.PgProvider;
import com.iseonjwa.payment.support.AppendOnlyCleanup;
import com.iseonjwa.payment.payment.domain.Payment;
import com.iseonjwa.payment.payment.domain.PaymentMethod;
import com.iseonjwa.payment.payment.domain.PaymentRepository;
import com.iseonjwa.payment.payment.domain.PaymentStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 재확인 테스트용 결제 행을 만들고 지운다. 재확인은 전역 후보를 집으므로 테스트는 트랜잭션 없이 커밋된 행으로 돌리고,
 * 끝나면 만든 행과 딸린 이력·Outbox를 지운다. 다른 테스트가 남긴 재확인 대상은 미래로 미뤄 둔다.
 */
public final class RecheckFixtures {
    public static final BigDecimal AMOUNT = BigDecimal.valueOf(50000);
    // 이 범위의 예약 ID는 재확인 테스트만 쓴다(다른 API 테스트의 범위와 겹치지 않는다)
    private static final long FIRST_RESERVATION = 8_700_000;
    private static final long LAST_RESERVATION = 8_799_999;
    private static final AtomicLong RESERVATION_IDS = new AtomicLong(FIRST_RESERVATION);

    private final PaymentRepository payments;
    private final JdbcTemplate jdbc;

    public RecheckFixtures(PaymentRepository payments, JdbcTemplate jdbc) {
        this.payments = payments;
        this.jdbc = jdbc;
    }

    /**
     * 재확인 대상(REQUESTED·UNKNOWN)을 모두 먼 미래로 미룬다. 이 테스트가 만들 행만 후보가 되게 하려는 것이다.
     * 테스트 도중 차례가 되는 다른 행이 끼어들지 않도록 조건 없이 전부 미루고, 행은 그 뒤에 만든다.
     */
    public void parkAllRecheckCandidates() {
        jdbc.update("UPDATE payments SET next_check_at = now() + interval '1 year' WHERE status IN ('REQUESTED', 'UNKNOWN')");
    }

    /** 지정한 결제만 빼고 재확인 대상을 모두 먼 미래로 미룬다. */
    public void parkAllExcept(long paymentId) {
        jdbc.update("UPDATE payments SET next_check_at = now() + interval '1 year' "
                + "WHERE status IN ('REQUESTED', 'UNKNOWN') AND id <> ?", paymentId);
    }

    /** 재확인 차례가 지난 결제를 만든다. {@code retryCount}는 이미 PG를 부른 횟수다. */
    public Payment due(PaymentStatus status, int retryCount) {
        return create(status, retryCount, Instant.now().minusSeconds(5));
    }

    /** 재확인 차례가 지난 결제를 지정한 PG로 만든다. */
    public Payment due(PaymentStatus status, int retryCount, PgProvider provider) {
        return create(status, retryCount, Instant.now().minusSeconds(5), provider);
    }

    public Payment create(PaymentStatus status, int retryCount, Instant nextCheckAt) {
        return create(status, retryCount, nextCheckAt, PgProvider.MOCK);
    }

    public Payment create(PaymentStatus status, int retryCount, Instant nextCheckAt, PgProvider provider) {
        var reservationId = RESERVATION_IDS.incrementAndGet();
        var saved = payments.save(Payment.request("PAY-R" + reservationId, reservationId, 1L, AMOUNT,
                PaymentMethod.CARD, UUID.randomUUID(), provider, Instant.now()));
        jdbc.update("UPDATE payments SET status = ?, unknown_retry_count = ?, next_check_at = ? WHERE id = ?",
                status.name(), retryCount, Timestamp.from(nextCheckAt), saved.getId());
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

    /** 이 범위의 결제와 딸린 이력·Outbox를 지운다. 테스트 시작 전과 끝에 부른다(다른 API 테스트와 같은 관례). */
    public void cleanUp() {
        jdbc.update("DELETE FROM outbox_events WHERE message_key ~ '^87[0-9]{5}$'");
        AppendOnlyCleanup.deleteHistories(jdbc, "payment_id IN "
                + "(SELECT id FROM payments WHERE reservation_id BETWEEN ? AND ?)", FIRST_RESERVATION, LAST_RESERVATION);
        jdbc.update("DELETE FROM payments WHERE reservation_id BETWEEN ? AND ?", FIRST_RESERVATION, LAST_RESERVATION);
    }
}
