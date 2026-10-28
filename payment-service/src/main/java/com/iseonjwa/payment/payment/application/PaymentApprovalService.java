package com.iseonjwa.payment.payment.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.payment.payment.domain.DeclineReason;
import com.iseonjwa.payment.payment.domain.IdempotencyRecord;
import com.iseonjwa.payment.payment.domain.IdempotencyRecordRepository;
import com.iseonjwa.payment.payment.domain.Payment;
import com.iseonjwa.payment.payment.domain.PaymentMethod;
import com.iseonjwa.payment.payment.domain.PaymentRepository;
import com.iseonjwa.payment.payment.domain.PaymentStatus;
import com.iseonjwa.payment.pg.application.ApproveCommand;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgIdempotencyKey;
import com.iseonjwa.payment.pg.application.PgOrderId;
import com.iseonjwa.payment.pg.application.UnknownCause;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 결제 승인 흐름(api.md 1절 처리 순서). 인증 → 멱등 기록 조회 → 토큰·금액 검증 → 같은 예매의 기존 결제 → TX1 →
 * PG 호출(트랜잭션 밖) → TX2 순서다. 이 클래스는 트랜잭션을 열지 않고 구간은 {@link PaymentTx}가 맡는다.
 *
 * <p>TODO: 신규 승인의 {@code pg-approve} 허가(Bulkhead·Circuit Breaker, 처리 순서 6단계)는 ISE-37에서 붙인다.
 * 그때까지 PG 호출 보호는 어댑터의 연결 0.5초·응답 2초 제한뿐이다.
 */
@Service
public class PaymentApprovalService {
    private static final Logger log = LoggerFactory.getLogger(PaymentApprovalService.class);
    // 유니크 위반 뒤 다시 조회하는 횟수. 한 번이면 충분하고 계속 겹치면 일시 장애로 본다
    private static final int MAX_ATTEMPTS = 2;
    private static final String RESERVATION_UNIQUE = "uq_payment_reservation_id";
    private static final String IDEMPOTENCY_PK = "idempotency_record_pkey";

    private final PaymentTokenVerifier tokens;
    private final PaymentRepository payments;
    private final IdempotencyRecordRepository idempotency;
    private final PaymentTx tx;
    private final PgClient pg;
    private final ObjectMapper mapper;
    private final Clock clock;

    public PaymentApprovalService(PaymentTokenVerifier tokens, PaymentRepository payments,
            IdempotencyRecordRepository idempotency, PaymentTx tx, PgClient pg, ObjectMapper mapper,
            @Qualifier("commonAuditingClock") Clock clock) {
        this.tokens = tokens;
        this.payments = payments;
        this.idempotency = idempotency;
        this.tx = tx;
        this.pg = pg;
        this.mapper = mapper;
        this.clock = clock;
    }

    public PaymentOutcome approve(ApprovePaymentCommand command) {
        // 2단계: 길이 상한은 해시 계산과 DB 조회보다 먼저 본다
        if (command.paymentToken().length() > PaymentTokenVerifier.MAX_TOKEN_LENGTH) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_TOKEN_INVALID);
        }
        try {
            return approveFlow(command);
        } catch (DataAccessException | TransactionException exception) {
            // PG를 부르기 전 구간의 DB 오류(연결 대기 초과 포함)다. 행이 만들어지지 않았거나 PG 호출 전이라 같은 키로 다시
            // 시도할 수 있다. PG 호출 뒤의 DB 오류는 settle이 잡아 202로 응답하므로 여기까지 오지 않는다
            log.warn("PG 호출 전 구간 DB 오류", exception);
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private PaymentOutcome approveFlow(ApprovePaymentCommand command) {
        var requestHash = PaymentRequestHash.of(command.paymentToken(), command.amount());
        for (var attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            // 3단계: 토큰 검증보다 먼저 같은 키의 기록을 본다. 토큰이 이미 만료됐어도 재시도가 결과를 받게 한다(0017)
            var replayed = replayByIdempotencyKey(command, requestHash);
            if (replayed.isPresent()) return replayed.get();

            // 4단계: 토큰·금액 검증
            var token = verifyToken(command);

            // 5단계: 같은 예매의 기존 결제
            var existing = payments.findByReservationId(token.reservationId());
            if (existing.isPresent()) return respondToExisting(command, token, requestHash, existing.get());
            if (token.isExpired(clock.instant())) {
                // 만료된 토큰은 조회 전용이다. 기존 결제가 없으면 신규 승인을 하지 않는다
                throw new BusinessException(PaymentErrorCode.PAYMENT_TOKEN_INVALID);
            }

            // 7단계: TX1. 이 try는 TX1만 감싼다. PG 호출·TX2의 예외가 여기로 들어오면 안 된다
            Payment payment;
            try {
                payment = tx.createRequested(command, token, requestHash);
            } catch (DataIntegrityViolationException exception) {
                if (!isExpectedConflict(exception)) throw exception;
                // 같은 키·같은 예매의 동시 요청이다. 500이 아니라 정상 흐름이므로 처음부터 다시 조회한다(0017·0026)
                log.info("TX1 유니크 충돌 attempt={}", attempt);
                continue;
            }
            return proceed(payment, command, token);
        }
        throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    // 8~10단계: 만료 재검사, PG 호출, TX2
    private PaymentOutcome proceed(Payment payment, ApprovePaymentCommand command, PaymentToken token) {
        if (token.isExpired(clock.instant())) {
            // PG를 부르지 않았으므로 미전송이 확실하다. 토큰이 TX1과 PG 호출 사이에 만료된 경우다(0018)
            return settle(payment, () -> tx.settleDeclined(payment, command, DeclineReason.TOKEN_EXPIRED));
        }
        return switch (callPg(payment)) {
            case PgClient.Approved approved -> settle(payment, () -> tx.settleApproved(payment, command, approved));
            case PgClient.Declined declined ->
                    settle(payment, () -> tx.settleDeclined(payment, command, declined.reason()));
            // 결과를 모르면 거절로 닫지 않는다. 재확인 스케줄러가 PG를 조회해 확정한다(INV-8)
            case PgClient.Unknown unknown -> settle(payment, () -> tx.settleUnknown(payment, command, unknown.cause()));
        };
    }

    // 포트는 결과를 예외가 아닌 타입으로 돌려주지만, 예외가 나오면 호출한 쪽이 결과를 모르는 것(Unknown)으로 다룬다
    private PgClient.ApproveResult callPg(Payment payment) {
        var orderId = new PgOrderId(payment.getPgOrderId());
        try {
            return pg.approve(new ApproveCommand(orderId, payment.getAmount(), payment.getCurrency(),
                    PgIdempotencyKey.ofApproval(orderId), null));
        } catch (RuntimeException exception) {
            log.error("PG 승인 호출 중 예외 paymentId={}", payment.getId(), exception);
            return new PgClient.Unknown(UnknownCause.PG_ERROR);
        }
    }

    // TX2가 어떤 이유로 실패해도 PG 결과는 이미 정해졌다. 202(REQUESTED)로 응답하면 재확인 스케줄러가 10초 뒤 확정한다.
    // 롤백됐으므로 상태·이력·Outbox·응답 저장은 모두 없던 일이다(INV-5)
    private PaymentOutcome settle(Payment payment, Supplier<PaymentOutcome> settlement) {
        try {
            return settlement.get();
        } catch (RuntimeException exception) {
            log.error("TX2 실패, 재확인 스케줄러가 확정한다 paymentId={}", payment.getId(), exception);
            return PaymentOutcome.of(payment);
        }
    }

    // 3단계
    private Optional<PaymentOutcome> replayByIdempotencyKey(ApprovePaymentCommand command, String requestHash) {
        var record = idempotency.find(command.userId(), command.idempotencyKey());
        if (record.isEmpty()) return Optional.empty();
        if (!record.get().getRequestHash().equals(requestHash)) {
            throw new BusinessException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return Optional.of(replay(record.get()));
    }

    private PaymentOutcome replay(IdempotencyRecord record) {
        if (record.hasStoredResponse()) {
            return PaymentOutcome.replay(record.getResponseStatus(), readStored(record.getResponseBody()));
        }
        // 처리 중이거나 서버가 죽은 건, ALIAS는 연결 결제의 현재 상태로 답한다(표 A)
        return PaymentOutcome.of(payments.findById(record.getResourceId()).orElseThrow());
    }

    private PaymentView readStored(String body) {
        return mapper.readValue(body, PaymentView.class);
    }

    // 4단계
    private PaymentToken verifyToken(ApprovePaymentCommand command) {
        var token = tokens.verify(command.paymentToken());
        var now = clock.instant();
        // 토큰은 다른 사용자의 것이어도 서명은 맞을 수 있다. 인증된 사용자와 같아야 한다
        if (token.userId() != command.userId() || token.isPastLookupGrace(now)) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_TOKEN_INVALID);
        }
        if (token.amount().compareTo(BigDecimal.valueOf(command.amount())) != 0) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
        return token;
    }

    // 5단계 표 B: 같은 예매에 결제가 이미 있을 때
    private PaymentOutcome respondToExisting(ApprovePaymentCommand command, PaymentToken token, String requestHash,
            Payment existing) {
        if (existing.getStatus() == PaymentStatus.DECLINED || existing.getStatus() == PaymentStatus.REFUNDED) {
            // 종료된 결제는 연결하지 않고 기록도 남기지 않는다(0016)
            return PaymentOutcome.rejected(PaymentErrorCode.PAYMENT_ALREADY_CLOSED);
        }
        if (!sameCoreInformation(command, token, existing)) {
            // 다른 사용자나 다른 조건의 결제를 돌려주지 않는다. Location도 주지 않아 존재를 드러내지 않는다
            return PaymentOutcome.rejected(PaymentErrorCode.PAYMENT_ALREADY_EXISTS);
        }
        if (tx.linkAlias(command, requestHash, existing)) {
            log.info("event=PAYMENT_LINKED_BY_NEW_KEY paymentId={} userId={} idemKey={}", existing.getId(),
                    command.userId(), command.idempotencyKey());
        } else {
            // 같은 새 키가 동시에 와서 다른 요청이 먼저 저장했다. 다시 읽어 같은 본문인지 확인한다(0019)
            replayByIdempotencyKey(command, requestHash);
        }
        return PaymentOutcome.of(existing);
    }

    // 사용자·예매·금액·통화·수단이 모두 같을 때만 같은 결제로 본다(0019)
    private static boolean sameCoreInformation(ApprovePaymentCommand command, PaymentToken token, Payment existing) {
        return existing.getUserId() == command.userId()
                && existing.getReservationId() == token.reservationId()
                && existing.getAmount().compareTo(token.amount()) == 0
                && existing.getAmount().compareTo(BigDecimal.valueOf(command.amount())) == 0
                && "KRW".equals(existing.getCurrency())
                && existing.getMethod() == PaymentMethod.CARD;
    }

    private static boolean isExpectedConflict(DataIntegrityViolationException exception) {
        var message = String.valueOf(exception.getMostSpecificCause().getMessage());
        return message.contains(RESERVATION_UNIQUE) || message.contains(IDEMPOTENCY_PK);
    }
}
