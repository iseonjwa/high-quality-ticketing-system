package com.iseonjwa.payment.pg.infrastructure;

import com.iseonjwa.payment.observability.PaymentMetrics;
import com.iseonjwa.payment.payment.domain.DeclineReason;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgPaymentRef;
import com.iseonjwa.payment.pg.application.UnknownCause;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// PG 호출 데코레이터가 호출마다 결과를 재고, 예외를 삼키지 않는지 확인한다(ISE-156)
class MeteredPgClientTest {
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final PgClient adapter = mock(PgClient.class);
    private PgClient client;

    @BeforeEach
    void setUp() {
        client = new MeteredPgClient(adapter, new PaymentMetrics(meters));
    }

    @Test
    void 결과_타입을_outcome_라벨로_기록하고_결과를_그대로_돌려준다() {
        var approved = new PgClient.Approved(new PgPaymentRef("pg-1"), BigDecimal.TEN, "KRW", Instant.now());
        var declined = new PgClient.Declined(DeclineReason.CARD_DECLINED);
        when(adapter.approve(any())).thenReturn(approved, declined);
        when(adapter.findPayment(any())).thenReturn(new PgClient.NotFound());
        when(adapter.preventLateApproval(any())).thenReturn(new PgClient.Prevented());
        when(adapter.refund(any())).thenReturn(new PgClient.Refunded("r-1", BigDecimal.TEN, "KRW", Instant.now()));
        when(adapter.findRefund(any(), any())).thenReturn(new PgClient.NotFound());

        assertSame(approved, client.approve(null));
        assertSame(declined, client.approve(null));
        client.findPayment(null);
        client.preventLateApproval(null);
        client.refund(null);
        client.findRefund(null, null);

        assertEquals(1, calls("approve", "approved"));
        assertEquals(1, calls("approve", "declined"));
        assertEquals(1, calls("find_payment", "not_found"));
        assertEquals(1, calls("prevent_late_approval", "prevented"));
        assertEquals(1, calls("refund", "refunded"));
        assertEquals(1, calls("find_refund", "not_found"));
    }

    @Test
    void Unknown이면_원인별_카운터도_올린다() {
        when(adapter.approve(any())).thenReturn(new PgClient.Unknown(UnknownCause.TIMEOUT));
        when(adapter.findPayment(any())).thenReturn(new PgClient.Unknown(UnknownCause.PG_ERROR));

        client.approve(null);
        client.approve(null);
        client.findPayment(null);

        assertEquals(2, calls("approve", "unknown"));
        assertEquals(2, unknown("approve", "timeout"));
        assertEquals(1, unknown("find_payment", "pg_error"));
        assertEquals(0, unknown("approve", "pg_error"));
    }

    @Test
    void 어댑터가_예외를_던지면_exception으로_기록하고_같은_예외를_다시_던진다() {
        var failure = new IllegalStateException("어댑터 장애");
        when(adapter.approve(any())).thenThrow(failure);

        var thrown = assertThrows(IllegalStateException.class, () -> client.approve(null));

        assertSame(failure, thrown);
        assertEquals(1, calls("approve", "exception"));
        assertEquals(0, unknown("approve", "pg_error"), "예외는 Unknown 원인으로 세지 않는다. 판단은 호출한 서비스의 몫이다");
    }

    @Test
    void 빌링키_호출도_지표_구멍이_되지_않는다() {
        when(adapter.issueBillingKey(any(), any())).thenReturn(new PgClient.BillingKeyRejected());
        when(adapter.approveBilling(any(), any())).thenReturn(new PgClient.Declined(DeclineReason.OTHER));

        client.issueBillingKey("auth", "customer");
        client.approveBilling(null, "billing");

        assertEquals(1, calls("issue_billing_key", "billing_key_rejected"));
        assertEquals(1, calls("approve_billing", "declined"));
    }

    private long calls(String operation, String outcome) {
        return meters.get("payment.pg.call").tag("operation", operation).tag("outcome", outcome).timer().count();
    }

    private double unknown(String operation, String cause) {
        return meters.get("payment.pg.unknown").tag("operation", operation).tag("cause", cause).counter().count();
    }
}
