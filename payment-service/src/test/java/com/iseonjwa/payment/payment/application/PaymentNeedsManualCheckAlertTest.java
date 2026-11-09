package com.iseonjwa.payment.payment.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.iseonjwa.payment.observability.PaymentAlertLog;
import com.iseonjwa.payment.payment.domain.Payment;
import com.iseonjwa.payment.payment.domain.PaymentRepository;
import com.iseonjwa.payment.payment.domain.PaymentStatus;
import com.iseonjwa.payment.payment.domain.StatusActor;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgPaymentRef;
import com.iseonjwa.payment.support.RecheckFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static com.iseonjwa.payment.support.RecheckFixtures.AMOUNT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 결제가 운영자 확인(NEEDS_MANUAL_CHECK)이 될 때 건별 알림 줄이 정확히 한 번, 커밋된 뒤에만 남는지 확인한다(ISE-183).
 * 줄의 필드 형식은 {@code PaymentAlertLogTest}가 고정하고, 여기서는 전이와 줄의 짝을 본다.
 */
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class PaymentNeedsManualCheckAlertTest {
    @Autowired
    PaymentTx tx;
    @Autowired
    PaymentTransitionService transitions;
    @Autowired
    PaymentRepository payments;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate transaction;

    private RecheckFixtures fixtures;
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(PaymentAlertLog.class);

    @BeforeEach
    void setUp() {
        fixtures = new RecheckFixtures(payments, jdbc);
        fixtures.cleanUp();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        fixtures.cleanUp();
    }

    @Test
    void 재조회를_소진해_결과를_모르면_PG_UNRESOLVED_줄이_한_번_남는다() {
        var payment = fixtures.due(PaymentStatus.UNKNOWN, 9);

        assertTrue(transitions.markUnresolved(payment.getId(), PaymentStatus.UNKNOWN, StatusActor.SYSTEM));

        assertAlert(payment, "reason=PG_UNRESOLVED");
    }

    @Test
    void TX2에서_PG_승인_금액이_다르면_PG_AMOUNT_MISMATCH_줄이_한_번_남는다() {
        var payment = fixtures.due(PaymentStatus.REQUESTED, 0);
        var command = new ApprovePaymentCommand(1L, UUID.randomUUID(), "unused-token", 50000L);
        var wrongAmount = new PgClient.Approved(new PgPaymentRef("pg-tx-mismatch"), BigDecimal.valueOf(40000), "KRW",
                Instant.now());

        tx.settleApproved(payment, command, wrongAmount);

        assertEquals(PaymentStatus.NEEDS_MANUAL_CHECK, fixtures.reload(payment).getStatus());
        assertAlert(payment, "reason=PG_AMOUNT_MISMATCH");
    }

    @Test
    void 승인_금액이_같으면_줄을_남기지_않는다() {
        var payment = fixtures.due(PaymentStatus.REQUESTED, 0);
        var command = new ApprovePaymentCommand(1L, UUID.randomUUID(), "unused-token", 50000L);

        tx.settleApproved(payment, command,
                new PgClient.Approved(new PgPaymentRef("pg-tx-ok"), AMOUNT, "KRW", Instant.now()));

        assertEquals(0, logs.list.size());
    }

    @Test
    void 트랜잭션이_롤백되면_줄을_남기지_않는다() {
        var payment = fixtures.due(PaymentStatus.UNKNOWN, 9);

        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            assertTrue(transitions.markUnresolved(payment.getId(), PaymentStatus.UNKNOWN, StatusActor.SYSTEM));
            assertEquals(0, logs.list.size(), "커밋 전에는 줄이 없다");
            throw new IllegalStateException("롤백을 흉내 낸다");
        }));

        assertEquals(PaymentStatus.UNKNOWN, fixtures.reload(payment).getStatus());
        assertEquals(0, logs.list.size());
    }

    @Test
    void 다른_경로가_먼저_상태를_바꿔_UPDATE가_0행이면_줄을_남기지_않는다() {
        var payment = fixtures.due(PaymentStatus.UNKNOWN, 9);
        // 기대한 상태(UNKNOWN)가 아니라 이미 APPROVED가 된 경우를 흉내 낸다
        jdbc.update("UPDATE payments SET status = 'APPROVED', pg_tx_id = 'pg-first', pg_approved_amount = 50000, "
                + "approved_at = now(), next_check_at = NULL WHERE id = ?", payment.getId());

        assertEquals(false, transitions.markUnresolved(payment.getId(), PaymentStatus.UNKNOWN, StatusActor.SYSTEM));

        assertEquals(0, logs.list.size());
    }

    // 줄이 한 건이고 ERROR이며, 결제·유저·예매 번호와 서울 시각이 순서대로 붙는다
    private void assertAlert(Payment payment, String reason) {
        assertEquals(1, logs.list.size());
        var event = logs.list.get(0);
        assertEquals(Level.ERROR, event.getLevel());
        var line = event.getFormattedMessage();
        assertTrue(line.matches("event=PAYMENT_NEEDS_MANUAL_CHECK paymentId=" + payment.getId() + " userId=1 reservationId="
                + payment.getReservationId() + " paidAt=\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\+09:00 " + reason), line);
    }
}
