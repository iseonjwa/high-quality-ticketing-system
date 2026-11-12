package com.iseonjwa.payment.payment.application;

import com.iseonjwa.payment.billing.application.BillingKeyEncryptor;
import com.iseonjwa.payment.billing.domain.BillingKey;
import com.iseonjwa.payment.billing.domain.BillingKeyRepository;
import com.iseonjwa.payment.payment.domain.DeclineReason;
import com.iseonjwa.payment.payment.domain.Payment;
import com.iseonjwa.payment.payment.domain.PaymentRepository;
import com.iseonjwa.payment.pg.application.ApproveCommand;
import com.iseonjwa.payment.pg.application.PgBillingGate;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgClients;
import com.iseonjwa.payment.pg.application.PgIdempotencyKey;
import com.iseonjwa.payment.pg.application.PgOrderId;
import com.iseonjwa.payment.pg.application.PgUnavailableException;
import com.iseonjwa.payment.pg.application.UnknownCause;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 추첨 당첨 자동결제(SA 6-6 F5-5, ISE-96). 승인 API와 같은 TX1 → PG(트랜잭션 밖) → TX2 구조를 쓰되 허가는 자동결제 전용
 * 그룹 {@code pg-billing}에서 얻는다(ADR 0051, ISE-236. 일반 결제의 {@code pg-approve}와 자리를 나눈다). 결과는 TX2의 전이가 {@code payment.result}로 내보낸다. Kafka 소비는 ISE-89가 이 서비스를 부른다.
 *
 * <p>Note: 같은 예매로 다시 와도 결제는 한 건이다. 기존 결제가 있으면 PG를 부르지 않고 그 결제를 돌려주고, 동시에 와서
 * {@code UNIQUE(reservation_id)}에 부딪혀도 다시 읽어 돌려준다(0026). outbox 릴레이가 같은 요청을 다시 보내는 경우다.
 */
@Service
public class BillingPaymentService {
    private static final Logger log = LoggerFactory.getLogger(BillingPaymentService.class);

    private final PaymentRepository payments;
    private final BillingKeyRepository billingKeys;
    private final BillingKeyEncryptor encryptor;
    private final PaymentTx tx;
    private final PgClients pgClients;
    private final PgBillingGate billingGate;

    public BillingPaymentService(PaymentRepository payments, BillingKeyRepository billingKeys,
            BillingKeyEncryptor encryptor, PaymentTx tx, PgClients pgClients, PgBillingGate billingGate) {
        this.payments = payments;
        this.billingKeys = billingKeys;
        this.encryptor = encryptor;
        this.tx = tx;
        this.pgClients = pgClients;
        this.billingGate = billingGate;
    }

    /**
     * 자동결제를 한 번 시도한다.
     *
     * @return 이 예매의 결제(새로 만들었거나 이미 있던 것)
     * @throws PgUnavailableException {@code pg-billing} 허가를 얻지 못했다. 결제 행을 만들지 않았으므로 호출한 쪽(소비자)이
     *                                메시지를 다시 처리하면 된다(백프레셔, ISE-89)
     */
    public Payment approve(BillingPaymentCommand command) {
        var existing = payments.findByReservationId(command.reservationId());
        if (existing.isPresent()) return existing.get();

        var billingKey = billingKeys.findActiveByUserId(command.userId());
        if (billingKey.isEmpty()) {
            // PG를 부르지 않으므로 허가가 필요 없다. 거절도 payment.result로 나가 추첨이 승계로 넘긴다(D4·D12)
            return create(command)
                    .map(payment -> tx.settleBillingDeclined(payment, DeclineReason.BILLING_KEY_MISSING))
                    .orElseGet(() -> reread(command));
        }
        try (var permit = billingGate.acquire()) {
            var created = create(command);
            if (created.isEmpty()) {
                permit.release();
                return reread(command);
            }
            var payment = created.get();
            permit.callStarted();
            var result = callPg(payment, billingKey.get());
            permit.settle(result);
            return settle(payment, result);
        }
    }

    // TX1. 같은 예매의 결제가 동시에 만들어졌으면 비어 있다
    private Optional<Payment> create(BillingPaymentCommand command) {
        try {
            return Optional.of(tx.createBillingRequested(command));
        } catch (DataIntegrityViolationException exception) {
            log.info("자동결제 TX1 유니크 충돌 reservationId={}", command.reservationId());
            return Optional.empty();
        }
    }

    private Payment reread(BillingPaymentCommand command) {
        return payments.findByReservationId(command.reservationId()).orElseThrow();
    }

    // 빌링키 원문은 이 메서드 안에서만 쓴다. 복호화 실패는 PG를 부르지 않았으니 결과를 모르는 것이 아니라 키 문제라 거절로 닫는다
    private PgClient.ApproveResult callPg(Payment payment, BillingKey billingKey) {
        String plain;
        try {
            plain = encryptor.decrypt(billingKey.getBillingKeyEnc());
        } catch (IllegalStateException exception) {
            log.error("빌링키를 복호화하지 못했다 billingKeyId={}", billingKey.getId(), exception);
            return new PgClient.Declined(DeclineReason.BILLING_KEY_MISSING);
        }
        var orderId = new PgOrderId(payment.getPgOrderId());
        var command = new ApproveCommand(orderId, payment.getAmount(), payment.getCurrency(),
                PgIdempotencyKey.ofApproval(orderId), null);
        try {
            // 빌링은 서버 PG 모드와 상관없이 늘 Mock으로 보낸다(ADR 0023 2026-11-10)
            return pgClients.billing().approveBilling(command, plain);
        } catch (RuntimeException exception) {
            log.error("PG 자동결제 호출 중 예외 paymentId={}", payment.getId(), exception);
            return new PgClient.Unknown(UnknownCause.PG_ERROR);
        }
    }

    // TX2가 실패해도 PG 결과는 정해졌다. REQUESTED로 남으면 재확인 스케줄러가 PG를 조회해 확정한다(승인 API와 같다)
    private Payment settle(Payment payment, PgClient.ApproveResult result) {
        try {
            return switch (result) {
                case PgClient.Approved approved -> tx.settleBillingApproved(payment, approved);
                case PgClient.Declined declined -> tx.settleBillingDeclined(payment, declined.reason());
                case PgClient.Unknown unknown -> tx.settleBillingUnknown(payment, unknown.cause());
            };
        } catch (RuntimeException exception) {
            log.error("자동결제 TX2 실패, 재확인 스케줄러가 확정한다 paymentId={}", payment.getId(), exception);
            return payment;
        }
    }
}
