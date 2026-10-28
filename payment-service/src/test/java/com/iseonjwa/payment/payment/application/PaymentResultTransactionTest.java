package com.iseonjwa.payment.payment.application;

import com.iseonjwa.common.messaging.outbox.OutboxWriter;
import com.iseonjwa.payment.payment.domain.DeclineReason;
import com.iseonjwa.payment.payment.domain.Payment;
import com.iseonjwa.payment.payment.domain.PaymentMethod;
import com.iseonjwa.payment.payment.domain.PaymentRepository;
import com.iseonjwa.payment.payment.domain.PaymentStatus;
import com.iseonjwa.payment.payment.domain.StatusActor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Note: 상태 전이와 Outbox가 정말 한 트랜잭션인지(JPA 트랜잭션 안의 JdbcTemplate 쓰기)는 테스트 트랜잭션으로 감싸지 않고
// 실제로 커밋·롤백해 확인한다.
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class PaymentResultTransactionTest {
    private static final long RESERVATION_ID = 3_100_001L;

    @Autowired
    PaymentTransitionService transitions;
    @Autowired
    PaymentRepository payments;
    @Autowired
    OutboxWriter outboxWriter;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM outbox WHERE message_key = ?", String.valueOf(RESERVATION_ID));
        jdbc.update("DELETE FROM payment_status_history WHERE payment_id IN (SELECT id FROM payment WHERE reservation_id = ?)",
                RESERVATION_ID);
        jdbc.update("DELETE FROM payment WHERE reservation_id = ?", RESERVATION_ID);
    }

    @Test
    void 트랜잭션이_롤백되면_상태_변경과_Outbox_행이_함께_사라진다() {
        var payment = payments.save(Payment.request("PAY-TX-1", RESERVATION_ID, 1L, BigDecimal.valueOf(50000),
                PaymentMethod.CARD, UUID.randomUUID(), Instant.now()));

        new TransactionTemplate(transactionManager).execute(status -> {
            transitions.approve(payment.getId(), PaymentStatus.REQUESTED, "pg-tx-tx", BigDecimal.valueOf(50000),
                    Instant.now(), StatusActor.SYSTEM);
            status.setRollbackOnly();
            return null;
        });

        assertEquals("REQUESTED", jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class,
                payment.getId()));
        assertEquals(0, outboxCount());
    }

    @Test
    void 트랜잭션이_커밋되면_상태_변경과_Outbox_행이_함께_남는다() {
        var payment = payments.save(Payment.request("PAY-TX-2", RESERVATION_ID, 1L, BigDecimal.valueOf(50000),
                PaymentMethod.CARD, UUID.randomUUID(), Instant.now()));

        new TransactionTemplate(transactionManager).execute(status -> transitions.decline(payment.getId(),
                PaymentStatus.REQUESTED, DeclineReason.OTHER, StatusActor.SYSTEM));

        assertEquals("DECLINED", jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class,
                payment.getId()));
        assertEquals(1, outboxCount());
    }

    @Test
    void Outbox_쓰기가_실패하면_상태_전이도_롤백된다() {
        var payment = payments.save(Payment.request("PAY-TX-3", RESERVATION_ID, 1L, BigDecimal.valueOf(50000),
                PaymentMethod.CARD, UUID.randomUUID(), Instant.now()));
        // Outbox INSERT만 실패하도록 이 예매 키를 거부하는 제약을 임시로 건다
        jdbc.execute("ALTER TABLE outbox ADD CONSTRAINT chk_test_block CHECK (message_key <> '" + RESERVATION_ID + "')");
        try {
            assertThrows(RuntimeException.class, () -> new TransactionTemplate(transactionManager).execute(status ->
                    transitions.decline(payment.getId(), PaymentStatus.REQUESTED, DeclineReason.OTHER,
                            StatusActor.SYSTEM)));
        } finally {
            jdbc.execute("ALTER TABLE outbox DROP CONSTRAINT chk_test_block");
        }

        assertEquals("REQUESTED", jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class,
                payment.getId()));
        assertEquals(0, outboxCount());
    }

    @Test
    void 트랜잭션_밖에서는_Outbox에_쓰지_못한다() {
        var exception = assertThrows(IllegalTransactionStateException.class,
                () -> outboxWriter.write("payment.result", "1", "PaymentApproved", 1, java.util.Map.of()));

        assertTrue(exception.getMessage().contains("트랜잭션"));
    }

    private int outboxCount() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE message_key = ?", Integer.class,
                String.valueOf(RESERVATION_ID));
    }
}
