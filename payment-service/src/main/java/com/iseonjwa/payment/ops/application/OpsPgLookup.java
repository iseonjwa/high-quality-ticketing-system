package com.iseonjwa.payment.ops.application;

import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgClient.LateApprovalResult;
import com.iseonjwa.payment.pg.application.PgClient.PaymentLookupResult;
import com.iseonjwa.payment.pg.application.PgClient.RefundLookupResult;
import com.iseonjwa.payment.pg.application.PgClient.Unknown;
import com.iseonjwa.payment.pg.application.PgOrderId;
import com.iseonjwa.payment.pg.application.PgPaymentRef;
import com.iseonjwa.payment.pg.application.PgRefundId;
import com.iseonjwa.payment.pg.application.UnknownCause;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Ops 명령이 PG를 부르는 유일한 자리. 결과를 모르면 어떤 이유로든(null, 예외, 클라이언트 없음) {@link Unknown}으로 돌려
 * 주고, 명령은 그것을 거부로 다룬다. 환불({@code PgClient.refund})은 일부러 노출하지 않는다(ADR 0028 결정 4).
 *
 * <p>Note: 복구 허가 게이트({@code PgRecoveryGate})를 거치지 않고 {@code PgClient}를 직접 부른다. 게이트의 Bulkhead는
 * 떠 있는 서비스 JVM의 복구 호출량을 지키는 장치이고, 이 JVM은 별도 프로세스라 그 허가와 공유되지 않는다. 명령 하나가 호출
 * 한두 번이라 이 JVM이 PG에 주는 부하도 사람이 한 건씩 부르는 수준이다.
 */
@Component
public class OpsPgLookup {
    private static final Logger log = LoggerFactory.getLogger(OpsPgLookup.class);

    private final ObjectProvider<PgClient> pgClient;

    public OpsPgLookup(ObjectProvider<PgClient> pgClient) {
        this.pgClient = pgClient;
    }

    public PaymentLookupResult findPayment(PgOrderId orderId) {
        var client = pgClient.getIfAvailable();
        if (client == null) return new Unknown(UnknownCause.CLIENT_ERROR);
        try {
            var result = client.findPayment(orderId);
            return result != null ? result : new Unknown(UnknownCause.PG_ERROR);
        } catch (RuntimeException exception) {
            log.error("PG 결제 조회 중 예외", exception);
            return new Unknown(UnknownCause.PG_ERROR);
        }
    }

    public RefundLookupResult findRefund(PgRefundId refundId, PgPaymentRef paymentRef) {
        var client = pgClient.getIfAvailable();
        if (client == null) return new Unknown(UnknownCause.CLIENT_ERROR);
        try {
            var result = client.findRefund(refundId, paymentRef);
            return result != null ? result : new Unknown(UnknownCause.PG_ERROR);
        } catch (RuntimeException exception) {
            log.error("PG 환불 조회 중 예외", exception);
            return new Unknown(UnknownCause.PG_ERROR);
        }
    }

    /** PG에 흔적을 남기는 호출이다. dry-run에서는 부르지 않고 적용 때만 부른다. */
    public LateApprovalResult preventLateApproval(PgOrderId orderId) {
        var client = pgClient.getIfAvailable();
        if (client == null) return new Unknown(UnknownCause.CLIENT_ERROR);
        try {
            var result = client.preventLateApproval(orderId);
            return result != null ? result : new Unknown(UnknownCause.PG_ERROR);
        } catch (RuntimeException exception) {
            log.error("PG 늦은 승인 방지 중 예외", exception);
            return new Unknown(UnknownCause.PG_ERROR);
        }
    }
}
