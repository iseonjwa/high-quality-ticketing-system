package com.iseonjwa.payment.payment.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// Note: 이미 커밋된 같은 (사용자, 키) 기록이 있을 때 두 번째 TX1이 조용히 넘어가지 않고 기본키 위반으로 롤백되는지 확인한다.
// 요청 수준의 동시 테스트는 이 위반이 아니라 재생 경로를 타서 이 회귀를 잡지 못한다.
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class PaymentTxIdempotencyTest {
    private static final long USER = 9_001L;
    private static final long RESERVATION_A = 8_300_001L;
    private static final long RESERVATION_B = 8_300_002L;

    @Autowired
    PaymentTx tx;
    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM idempotency_records WHERE user_id = ?", USER);
        jdbc.update("DELETE FROM payments WHERE reservation_id IN (?, ?)", RESERVATION_A, RESERVATION_B);
    }

    @Test
    void 같은_사용자와_키의_기록이_이미_있으면_두_번째_TX1은_위반으로_롤백되고_결제도_남지_않는다() {
        var key = UUID.randomUUID();
        var expiresAt = Instant.now().plusSeconds(290);
        tx.createRequested(new ApprovePaymentCommand(USER, key, "token-a", 50000),
                new PaymentToken(USER, RESERVATION_A, BigDecimal.valueOf(50000), expiresAt), "a".repeat(64));

        assertThrows(DataIntegrityViolationException.class, () -> tx.createRequested(
                new ApprovePaymentCommand(USER, key, "token-b", 50000),
                new PaymentToken(USER, RESERVATION_B, BigDecimal.valueOf(50000), expiresAt), "b".repeat(64)));

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM payments WHERE reservation_id = ?", Integer.class,
                RESERVATION_B), "두 번째 결제는 롤백된다");
        assertEquals("a".repeat(64), jdbc.queryForObject("SELECT request_hash FROM idempotency_records WHERE user_id = ?",
                String.class, USER).trim(), "기존 기록은 그대로다");
    }
}
