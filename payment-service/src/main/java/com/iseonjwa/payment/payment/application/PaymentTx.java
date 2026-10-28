package com.iseonjwa.payment.payment.application;

import com.iseonjwa.payment.payment.domain.DeclineReason;
import com.iseonjwa.payment.payment.domain.IdempotencyRecord;
import com.iseonjwa.payment.payment.domain.IdempotencyRecordRepository;
import com.iseonjwa.payment.payment.domain.Payment;
import com.iseonjwa.payment.payment.domain.PaymentMethod;
import com.iseonjwa.payment.payment.domain.PaymentRepository;
import com.iseonjwa.payment.payment.domain.PaymentStatus;
import com.iseonjwa.payment.payment.domain.StatusActor;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.UnknownCause;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * 승인 요청의 트랜잭션 구간(TX1·TX2). 승인 흐름이 PG 호출을 트랜잭션 밖에 두려고 구간마다 따로 커밋하므로,
 * 흐름을 맡은 {@link PaymentApprovalService}와 다른 빈으로 둔다(같은 빈 안에서 부르면 트랜잭션 프록시가 걸리지 않는다).
 */
@Component
public class PaymentTx {
    // UNKNOWN이 된 결제를 재확인 스케줄러가 처음 다시 조회하는 시점(SA 9-4의 첫 간격)
    private static final Duration FIRST_RECHECK_DELAY = Duration.ofSeconds(10);

    private final PaymentRepository payments;
    private final IdempotencyRecordRepository idempotency;
    private final PaymentTransitionService transitions;
    private final PaymentNoGenerator paymentNos;
    private final ObjectMapper mapper;
    private final Clock clock;

    public PaymentTx(PaymentRepository payments, IdempotencyRecordRepository idempotency,
            PaymentTransitionService transitions, PaymentNoGenerator paymentNos, ObjectMapper mapper,
            @Qualifier("commonAuditingClock") Clock clock) {
        this.payments = payments;
        this.idempotency = idempotency;
        this.transitions = transitions;
        this.paymentNos = paymentNos;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * TX1: 결제 {@code REQUESTED}와 멱등 기록(ORIGIN)을 한 트랜잭션으로 저장한다.
     * 같은 예매의 결제나 같은 키의 기록이 이미 있으면 유니크 위반(DataIntegrityViolationException)이고,
     * 호출한 쪽이 이를 정상 흐름으로 보고 다시 조회한다(0017·0026).
     *
     * <p>Note: 설계 문서(api.md 7단계)는 멱등 기록을 먼저 쓰라고 하지만 {@code resource_id}가 NOT NULL이라
     * 결제 ID를 알아야 해서 결제를 먼저 쓴다. 두 쓰기는 한 트랜잭션이고, 어느 쪽 위반이든 전체가 롤백돼 결과는 같다.
     */
    @Transactional
    public Payment createRequested(ApprovePaymentCommand command, PaymentToken token, String requestHash) {
        var now = clock.instant();
        var payment = payments.save(Payment.request(paymentNos.next(), token.reservationId(), command.userId(),
                token.amount(), PaymentMethod.CARD, UUID.randomUUID(), now));
        idempotency.save(IdempotencyRecord.origin(command.userId(), command.idempotencyKey(), requestHash,
                payment.getId(), now));
        return payment;
    }

    /** 새 키를 기존 결제에 연결한다(ALIAS). 같은 키가 동시에 오면 한쪽만 저장되고 둘 다 같은 응답을 받는다. */
    @Transactional
    public void linkAlias(ApprovePaymentCommand command, String requestHash, Payment existing) {
        idempotency.linkAlias(command.userId(), command.idempotencyKey(), requestHash, existing.getId(),
                clock.instant());
    }

    /**
     * TX2(승인): 금액·통화가 결제와 같으면 {@code APPROVED}, 다르면 {@code NEEDS_MANUAL_CHECK(PG_AMOUNT_MISMATCH)}로
     * 둔다(INV-3). 승인이면 이력·Outbox·멱등 응답을 같은 트랜잭션에 쓴다.
     */
    @Transactional
    public PaymentOutcome settleApproved(Payment payment, ApprovePaymentCommand command, PgClient.Approved result) {
        boolean changed;
        if (result.matches(payment.getAmount(), payment.getCurrency())) {
            changed = transitions.approve(payment.getId(), PaymentStatus.REQUESTED, result.paymentRef().value(),
                    result.amount(), result.approvedAt(), StatusActor.SYSTEM);
        } else {
            changed = transitions.markAmountMismatch(payment.getId(), PaymentStatus.REQUESTED,
                    result.paymentRef().value(), result.amount(), result.approvedAt(), StatusActor.SYSTEM);
        }
        return outcomeAfterTransition(payment, command, changed);
    }

    /** TX2(거절). PG 호출 전 토큰 만료로 닫는 경우({@code TOKEN_EXPIRED})도 같은 경로다. */
    @Transactional
    public PaymentOutcome settleDeclined(Payment payment, ApprovePaymentCommand command, DeclineReason reason) {
        var changed = transitions.decline(payment.getId(), PaymentStatus.REQUESTED, reason, StatusActor.SYSTEM);
        return outcomeAfterTransition(payment, command, changed);
    }

    /** TX2(결과 불확실). 재확인 스케줄러가 이어받도록 {@code UNKNOWN}으로 두고 202로 응답한다. */
    @Transactional
    public PaymentOutcome settleUnknown(Payment payment, ApprovePaymentCommand command, UnknownCause cause) {
        var changed = transitions.markUnknown(payment.getId(), PaymentStatus.REQUESTED,
                clock.instant().plus(FIRST_RECHECK_DELAY), cause.name(), StatusActor.SYSTEM);
        return outcomeAfterTransition(payment, command, changed);
    }

    // 전이가 실제로 일어났을 때만 응답을 저장한다. 0행이면 다른 경로가 먼저 바꾼 것이라 현재 상태로 답한다(INV-5)
    private PaymentOutcome outcomeAfterTransition(Payment payment, ApprovePaymentCommand command, boolean changed) {
        var current = payments.findById(payment.getId()).orElseThrow();
        var outcome = PaymentOutcome.of(current);
        // 저장하는 응답은 200·202뿐이다. 확인 필요(409)는 저장하지 않는다(0017)
        if (changed && !outcome.isError()) {
            idempotency.saveResponse(command.userId(), command.idempotencyKey(), outcome.httpStatus(),
                    mapper.writeValueAsString(outcome.data()));
        }
        return outcome;
    }
}
