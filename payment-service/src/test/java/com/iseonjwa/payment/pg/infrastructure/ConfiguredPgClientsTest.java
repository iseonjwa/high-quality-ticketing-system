package com.iseonjwa.payment.pg.infrastructure;

import com.iseonjwa.payment.payment.domain.DeclineReason;
import com.iseonjwa.payment.payment.domain.PgProvider;
import com.iseonjwa.payment.pg.application.ApproveCommand;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgIdempotencyKey;
import com.iseonjwa.payment.pg.application.PgOrderId;
import com.iseonjwa.payment.pg.application.PgPaymentRef;
import com.iseonjwa.payment.pg.application.PgRefundId;
import com.iseonjwa.payment.pg.application.RefundCommand;
import com.iseonjwa.payment.pg.application.UnknownCause;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ConfiguredPgClientsTest {
    private static final PgClient.Unknown CLIENT_ERROR = new PgClient.Unknown(UnknownCause.CLIENT_ERROR);

    private final PgClient mockPg = mock(PgClient.class);

    @Test
    void 설정된_PG는_그_클라이언트를_돌려준다() {
        var toss = mock(PgClient.class);
        var clients = new ConfiguredPgClients(mockPg, toss);

        assertSame(mockPg, clients.of(PgProvider.MOCK));
        assertSame(toss, clients.of(PgProvider.TOSS));
        assertSame(mockPg, clients.billing());
    }

    @Test
    void 토스가_없으면_TOSS의_다섯_메서드가_PG를_부르지_않고_CLIENT_ERROR로_답한다() {
        var client = new ConfiguredPgClients(mockPg, null).of(PgProvider.TOSS);
        var orderId = new PgOrderId(UUID.randomUUID());
        var paymentRef = new PgPaymentRef("tgen_x");
        var refundId = new PgRefundId(UUID.randomUUID());

        assertEquals(CLIENT_ERROR, client.approve(new ApproveCommand(orderId, BigDecimal.valueOf(1000), "KRW",
                PgIdempotencyKey.ofApproval(orderId), paymentRef)));
        assertEquals(CLIENT_ERROR, client.findPayment(orderId));
        assertEquals(CLIENT_ERROR, client.preventLateApproval(orderId));
        assertEquals(CLIENT_ERROR, client.refund(new RefundCommand(paymentRef, refundId, BigDecimal.valueOf(1000), "KRW",
                "TEST", PgIdempotencyKey.ofRefund(refundId))));
        assertEquals(CLIENT_ERROR, client.findRefund(refundId, paymentRef));
        assertEquals(CLIENT_ERROR, client.issueBillingKey("auth", "customer"));
        assertEquals(new PgClient.Declined(DeclineReason.NOT_SENT),
                client.approveBilling(new ApproveCommand(orderId, BigDecimal.valueOf(1000), "KRW",
                        PgIdempotencyKey.ofApproval(orderId), null), "bk"));
        verifyNoInteractions(mockPg);
    }
}
