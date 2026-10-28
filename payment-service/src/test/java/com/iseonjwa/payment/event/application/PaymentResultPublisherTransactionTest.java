package com.iseonjwa.payment.event.application;

import com.iseonjwa.payment.payment.domain.Payment;
import com.iseonjwa.payment.payment.domain.PaymentMethod;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

// Note: 테스트 트랜잭션 없이 호출해 "상태 전이와 같은 트랜잭션에서만 쓴다"는 규칙(MANDATORY)을 확인한다.
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class PaymentResultPublisherTransactionTest {
    @Autowired
    PaymentResultPublisher publisher;

    @Test
    void 트랜잭션_밖에서는_이벤트를_쓰지_못한다() {
        var payment = Payment.request("PAY-TX", 1L, 1L, BigDecimal.valueOf(50000), PaymentMethod.CARD,
                UUID.randomUUID(), Instant.now());

        assertThrows(IllegalTransactionStateException.class, () -> publisher.publishApproved(payment));
    }
}
