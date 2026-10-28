package com.iseonjwa.payment.payment.application;

import com.iseonjwa.payment.payment.domain.DeclineReason;
import com.iseonjwa.payment.payment.domain.Payment;
import com.iseonjwa.payment.payment.domain.PaymentMethod;
import com.iseonjwa.payment.payment.domain.PaymentRepository;
import com.iseonjwa.payment.payment.domain.PaymentStatus;
import com.iseonjwa.payment.payment.domain.PaymentStatusHistoryRepository;
import com.iseonjwa.payment.payment.domain.StatusActor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Note: 서로 다른 트랜잭션이 같은 결제를 동시에 바꾸는 상황이라 테스트 트랜잭션으로 감싸지 않고 실제로 커밋한다.
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class PaymentTransitionConcurrencyTest {
    private static final long RESERVATION_ID = 3_000_001L;

    @Autowired
    PaymentTransitionService transitions;
    @Autowired
    PaymentRepository payments;
    @Autowired
    PaymentStatusHistoryRepository histories;
    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM payment_status_history WHERE payment_id IN "
                + "(SELECT id FROM payment WHERE reservation_id = ?)", RESERVATION_ID);
        jdbc.update("DELETE FROM payment WHERE reservation_id = ?", RESERVATION_ID);
    }

    @Test
    void 승인과_거절이_동시에_와도_하나만_성공하고_이력은_한_건이다() throws Exception {
        var payment = payments.save(Payment.request("PAY-CONCURRENT", RESERVATION_ID, 1L, BigDecimal.valueOf(50000),
                PaymentMethod.CARD, UUID.randomUUID(), Instant.now()));
        var start = new CountDownLatch(1);
        Callable<Boolean> approve = () -> {
            start.await();
            return transitions.approve(payment.getId(), PaymentStatus.REQUESTED, "pg-tx-race",
                    BigDecimal.valueOf(50000), StatusActor.SYSTEM);
        };
        Callable<Boolean> decline = () -> {
            start.await();
            return transitions.decline(payment.getId(), PaymentStatus.REQUESTED, DeclineReason.OTHER,
                    StatusActor.SYSTEM);
        };

        var executor = Executors.newFixedThreadPool(2);
        try {
            var results = List.of(executor.submit(approve), executor.submit(decline));
            start.countDown();
            var succeeded = 0;
            for (var result : results) {
                if (result.get()) succeeded++;
            }

            assertEquals(1, succeeded, "조건부 UPDATE는 한 경로만 통과시킨다");
            assertEquals(1, histories.findByPaymentId(payment.getId()).size());
        } finally {
            executor.shutdownNow();
        }
    }
}
