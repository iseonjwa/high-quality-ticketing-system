package com.iseonjwa.payment.pg.infrastructure;

import com.iseonjwa.payment.pg.application.ApproveCommand;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgIdempotencyKey;
import com.iseonjwa.payment.pg.application.PgOrderId;
import com.iseonjwa.payment.pg.application.PgPaymentRef;
import com.iseonjwa.payment.pg.application.UnknownCause;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 토스 테스트 환경을 실제로 부르는 통합 테스트다. 기본 {@code test} 태스크는 {@code toss-sandbox} 태그를 뺀다.
 * {@code TOSS_TEST_SECRET_KEY}(test_sk_로 시작)가 없으면 건너뛴다. 실행: {@code ./gradlew :payment-service:tossSandboxTest}
 *
 * <p>Note: 자동으로 확인하는 것은 인증 실패 분류와 없는 주문 조회뿐이다. 결제 승인은 구매자가 결제창에서 인증해야 해서
 * 사람이 시연으로 확인한다(deployment.md의 토스 시연 절차). 키 값은 로그나 단언 메시지에 넣지 않는다.
 */
@Tag("toss-sandbox")
@EnabledIfEnvironmentVariable(named = "TOSS_TEST_SECRET_KEY", matches = ".+")
class TossPgAdapterSandboxTest {
    private static final String BASE_URL = "https://api.tosspayments.com";
    // 시크릿 키 형식만 맞춘 가짜 값이다. 토스는 인증 실패(401)로 답한다
    private static final String INVALID_KEY = "test_sk_sandbox_test_invalid_key";

    private static String secretKey;

    @BeforeAll
    static void requireTestKey() {
        secretKey = System.getenv("TOSS_TEST_SECRET_KEY");
        // 라이브 키로 실수로 돌리면 실제 결제 API를 부르게 되므로 건너뛰지 않고 실패시킨다
        assertTrue(secretKey.startsWith("test_sk_"), "TOSS_TEST_SECRET_KEY는 test_sk_로 시작하는 테스트 키여야 한다");
    }

    @Test
    void 잘못된_시크릿_키는_거절이_아니라_CLIENT_ERROR인_Unknown이다() {
        var client = TossPgAdapter.create(BASE_URL, INVALID_KEY, Duration.ofSeconds(3), Duration.ofSeconds(10));
        var order = new PgOrderId(UUID.randomUUID());
        var command = new ApproveCommand(order, BigDecimal.valueOf(1000), "KRW", PgIdempotencyKey.ofApproval(order),
                new PgPaymentRef("tgen_sandbox_unused_" + UUID.randomUUID().toString().replace("-", "")));

        var result = client.approve(command);

        assertEquals(new PgClient.Unknown(UnknownCause.CLIENT_ERROR), result);
    }

    @Test
    void 없는_주문을_조회하면_NotFound다() {
        var client = TossPgAdapter.create(BASE_URL, secretKey, Duration.ofSeconds(3), Duration.ofSeconds(10));

        var result = client.findPayment(new PgOrderId(UUID.randomUUID()));

        assertInstanceOf(PgClient.NotFound.class, result);
    }
}
