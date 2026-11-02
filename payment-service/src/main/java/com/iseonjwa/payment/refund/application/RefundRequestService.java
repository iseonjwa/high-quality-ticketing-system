package com.iseonjwa.payment.refund.application;

import com.iseonjwa.common.messaging.inbox.ProcessedEventService;
import com.iseonjwa.payment.payment.domain.Payment;
import com.iseonjwa.payment.payment.domain.PaymentRepository;
import com.iseonjwa.payment.payment.domain.PaymentStatusHistory;
import com.iseonjwa.payment.payment.domain.PaymentStatusHistoryRepository;
import com.iseonjwa.payment.payment.domain.StatusActor;
import com.iseonjwa.payment.refund.domain.RefundReason;
import com.iseonjwa.payment.refund.domain.RefundRepository;
import com.iseonjwa.payment.refund.domain.RefundSource;
import com.iseonjwa.payment.refund.domain.RefundStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code RefundRequested}를 결제와 교차 검증하고 환불을 DB에 영속화한다(events-and-reliability.md 4절).
 * PG는 부르지 않는다. PG 환불은 환불 워커(ISE-137)가 한다.
 */
@Service
public class RefundRequestService {
    public static final String CONSUMER_GROUP = "payment.command";

    private final ProcessedEventService processedEvents;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final PaymentStatusHistoryRepository histories;
    private final Clock clock;

    public RefundRequestService(ProcessedEventService processedEvents, PaymentRepository payments,
            RefundRepository refunds, PaymentStatusHistoryRepository histories,
            @Qualifier("commonAuditingClock") Clock clock) {
        this.processedEvents = processedEvents;
        this.payments = payments;
        this.refunds = refunds;
        this.histories = histories;
        this.clock = clock;
    }

    /**
     * 처리 이력과 환불·이력 쓰기를 한 트랜잭션에서 한다. 이미 처리한 eventId면 아무것도 하지 않는다.
     *
     * @throws NonRetryableMessageException 검증에 실패했을 때. 트랜잭션이 롤백되어 처리 이력도 남지 않는다
     */
    public void handle(UUID eventId, RefundRequestedPayload payload) {
        processedEvents.processOnce(eventId, CONSUMER_GROUP, () -> persist(eventId, payload));
    }

    private void persist(UUID eventId, RefundRequestedPayload payload) {
        var payment = payments.findById(payload.paymentId())
                .orElseThrow(() -> new NonRetryableMessageException("결제가 없습니다: paymentId=" + payload.paymentId()));
        var reason = verify(payment, payload);

        var now = clock.instant();
        var created = refunds.createForApprovedPayment(payment.getId(), payment.getReservationId(), UUID.randomUUID(),
                reason, RefundSource.EVENT, eventId, now);
        if (created == 1) {
            // 실제로 만들었을 때만 이력을 쓴다(domain-and-states.md 4절 1번)
            histories.save(PaymentStatusHistory.ofRefund(payment.getId(), null, RefundStatus.REQUESTED, reason.name(),
                    StatusActor.SYSTEM, now));
            return;
        }
        // Note: 0행이면 결제 상태를 보기 전에 환불 행부터 다시 읽는다. 이미 환불돼 결제가 REFUNDED여도 환불 행이 있으면
        // 중복 요청이라 정상 처리해야 하는데, 결제 상태로 먼저 거르면 이를 영구 오류로 오판한다.
        if (refunds.findByPaymentId(payment.getId()).isPresent()) {
            return;
        }
        throw new NonRetryableMessageException(
                "환불을 시작할 수 없는 결제 상태입니다: paymentId=" + payment.getId() + ", status=" + payment.getStatus());
    }

    // 결제와 이벤트 값을 대조한다(INV-3·INV-9). 이벤트의 금액은 대조에만 쓰고 환불 금액은 결제의 승인 금액을 복사한다
    private RefundReason verify(Payment payment, RefundRequestedPayload payload) {
        if (!Objects.equals(payment.getReservationId(), payload.reservationId())) {
            throw mismatch("reservationId", payment.getReservationId(), payload.reservationId());
        }
        if (!Objects.equals(payment.getUserId(), payload.userId())) {
            throw mismatch("userId", payment.getUserId(), payload.userId());
        }
        if (payload.amount() == null || payment.getAmount().compareTo(payload.amount()) != 0) {
            throw mismatch("amount", payment.getAmount(), payload.amount());
        }
        if (!Objects.equals(payment.getCurrency(), payload.currency())) {
            throw mismatch("currency", payment.getCurrency(), payload.currency());
        }
        return RefundReason.fromEvent(payload.reason()).orElseThrow(
                () -> new NonRetryableMessageException("이벤트로 요청할 수 없는 환불 사유입니다: " + payload.reason()));
    }

    private static NonRetryableMessageException mismatch(String field, Object payment, Object event) {
        return new NonRetryableMessageException("결제와 이벤트의 " + field + "가 다릅니다: 결제=" + payment + ", 이벤트=" + event);
    }
}
