package com.iseonjwa.payment.refund.application;

import com.iseonjwa.common.messaging.consumer.NonRetryableMessageException;
import com.iseonjwa.common.messaging.inbox.ProcessedEventService;
import com.iseonjwa.payment.payment.domain.PaymentRepository;
import com.iseonjwa.payment.payment.domain.PaymentStatus;
import com.iseonjwa.payment.payment.domain.PaymentStatusHistory;
import com.iseonjwa.payment.payment.domain.PaymentStatusHistoryRepository;
import com.iseonjwa.payment.payment.domain.StatusActor;
import com.iseonjwa.payment.refund.domain.RefundReason;
import com.iseonjwa.payment.refund.domain.RefundRepository;
import com.iseonjwa.payment.refund.domain.RefundSource;
import com.iseonjwa.payment.refund.domain.RefundStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code RefundRequested}를 결제와 교차 검증하고 환불을 DB에 영속화한다(events-and-reliability.md 4절).
 * PG는 부르지 않는다. PG 환불은 환불 워커(ISE-137)가 한다.
 *
 * <p>Note: 검증({@link #verify})과 생성({@link #create})은 내용 문제를 예외가 아닌 결과 값으로 돌려준다. 소비자 경로는
 * 그 값을 예외로 바꿔 던지지만, DLQ 처리기는 바깥 트랜잭션 안에서 내용 문제를 {@code OPEN} 기록으로 닫고 커밋해야 한다.
 * 바깥 트랜잭션에 참여한 코드가 예외를 던지면 그 트랜잭션이 rollback-only가 되어, 예외를 삼켜도 커밋할 때
 * {@code UnexpectedRollbackException}이 나기 때문이다({@code ProcessedEventService.processOnce}의 Note).
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
        switch (verify(payload)) {
            case RefundRequestVerification.Invalid invalid -> throw new NonRetryableMessageException(invalid.message());
            case RefundRequestVerification.Valid valid -> {
                if (create(payload, valid.reason(), RefundSource.EVENT, eventId) instanceof RefundCreation.NotApproved notApproved) {
                    throw new NonRetryableMessageException(notApproved.message());
                }
            }
        }
    }

    /**
     * 결제와 이벤트 값을 대조한다(INV-3·INV-9). 읽기만 하고 내용 문제를 예외로 던지지 않는다. 이벤트의 금액은 대조에만 쓰고
     * 환불 금액은 결제의 승인 금액을 복사한다.
     */
    public RefundRequestVerification verify(RefundRequestedPayload payload) {
        var found = payments.findById(payload.paymentId());
        if (found.isEmpty()) {
            return new RefundRequestVerification.Invalid("결제가 없습니다: paymentId=" + payload.paymentId());
        }
        var payment = found.get();
        if (!Objects.equals(payment.getReservationId(), payload.reservationId())) {
            return mismatch("reservationId", payment.getReservationId(), payload.reservationId());
        }
        if (!Objects.equals(payment.getUserId(), payload.userId())) {
            return mismatch("userId", payment.getUserId(), payload.userId());
        }
        if (payload.amount() == null || payment.getAmount().compareTo(payload.amount()) != 0) {
            return mismatch("amount", payment.getAmount(), payload.amount());
        }
        if (!Objects.equals(payment.getCurrency(), payload.currency())) {
            return mismatch("currency", payment.getCurrency(), payload.currency());
        }
        return RefundReason.fromEvent(payload.reason())
                .<RefundRequestVerification>map(RefundRequestVerification.Valid::new)
                .orElseGet(() -> new RefundRequestVerification.Invalid("이벤트로 요청할 수 없는 환불 사유입니다: " + payload.reason()));
    }

    /**
     * 검증을 통과한 요청으로 환불을 원자적으로 만든다. 호출한 쪽의 트랜잭션에 참여하며 예외로 내용 문제를 알리지 않는다.
     * {@code payload}는 {@link #verify}를 통과한 것이어야 한다(결제와 예매 ID가 맞다).
     */
    public RefundCreation create(RefundRequestedPayload payload, RefundReason reason, RefundSource source, UUID eventId) {
        var now = clock.instant();
        var created = refunds.createForApprovedPayment(payload.paymentId(), payload.reservationId(), UUID.randomUUID(),
                reason, source, eventId, now);
        if (created == 1) {
            // 실제로 만들었을 때만 이력을 쓴다(domain-and-states.md 4절 1번)
            histories.save(PaymentStatusHistory.ofRefund(payload.paymentId(), null, RefundStatus.REQUESTED, reason.name(),
                    StatusActor.SYSTEM, now));
            return new RefundCreation.Created(refunds.findByPaymentId(payload.paymentId()).orElseThrow().getId());
        }
        // Note: 0행이면 결제 상태를 보기 전에 환불 행부터 다시 읽는다. 이미 환불돼 결제가 REFUNDED여도 환불 행이 있으면
        // 중복 요청이라 정상 처리해야 하는데, 결제 상태로 먼저 거르면 이를 영구 오류로 오판한다.
        var existing = refunds.findByPaymentId(payload.paymentId());
        if (existing.isPresent()) {
            return new RefundCreation.Duplicate(existing.get().getId());
        }
        var status = payments.findById(payload.paymentId()).map(payment -> payment.getStatus().name()).orElse("없음");
        return new RefundCreation.NotApproved(
                "환불을 시작할 수 없는 결제 상태입니다: paymentId=" + payload.paymentId() + ", status=" + status);
    }

    /**
     * Ops {@code payment resolve}가 PG 승인 금액이 결제 금액과 다른 {@code NEEDS_MANUAL_CHECK} 결제의 환불을 시작한다.
     * 결제는 {@code NEEDS_MANUAL_CHECK}로 두고, 환불 워커가 PG 환불을 끝내면 결제가 {@code REFUNDED}가 된다(워커의
     * {@code paymentStatusBeforeRefund}). 호출한 쪽의 트랜잭션에 참여하며 PG는 부르지 않는다.
     *
     * <p>Note: 워커가 환불할 수 있도록 결제에 PG 거래 식별자와 승인 금액을 남긴다. 재확인이 금액 불일치로 넘긴 결제는 이미
     * 값이 있지만, 재조회를 소진해 넘어온 결제는 비어 있어 비워 둔 채로는 워커가 {@code STATE_CONFLICT}로 멈추고
     * {@code PaymentRefunded}의 금액도 비게 된다. {@code COALESCE}라 이미 있는 값은 바꾸지 않는다(그 값이 PG와 다른 건은
     * 호출한 쪽이 계획 단계에서 거부한다). 결제의 상태 전이가 아니므로 결제 이력은 쓰지 않고 환불 생성 이력에 사유를 남긴다.
     */
    public RefundCreation createForAmountMismatch(Long paymentId, String pgTxId, BigDecimal pgApprovedAmount,
            Instant pgApprovedAt, StatusActor actor) {
        var now = clock.instant();
        var existing = refunds.findByPaymentId(paymentId);
        if (existing.isPresent()) {
            return new RefundCreation.Duplicate(existing.get().getId());
        }
        if (!payments.markNeedsManualCheck(paymentId, PaymentStatus.NEEDS_MANUAL_CHECK, pgTxId, pgApprovedAmount,
                pgApprovedAt, now)) {
            return new RefundCreation.NotApproved("환불을 시작할 수 없는 결제 상태입니다: paymentId=" + paymentId);
        }
        if (refunds.createForAmountMismatch(paymentId, UUID.randomUUID(), pgApprovedAmount, now) != 1) {
            return new RefundCreation.NotApproved("금액 불일치 환불을 만들지 못했습니다: paymentId=" + paymentId);
        }
        histories.save(PaymentStatusHistory.ofRefund(paymentId, null, RefundStatus.REQUESTED,
                RefundReason.PG_AMOUNT_MISMATCH.name(), actor, now));
        return new RefundCreation.Created(refunds.findByPaymentId(paymentId).orElseThrow().getId());
    }

    private static RefundRequestVerification.Invalid mismatch(String field, Object payment, Object event) {
        return new RefundRequestVerification.Invalid("결제와 이벤트의 " + field + "가 다릅니다: 결제=" + payment + ", 이벤트=" + event);
    }
}
