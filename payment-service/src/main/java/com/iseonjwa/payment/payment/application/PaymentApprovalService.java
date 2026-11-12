package com.iseonjwa.payment.payment.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.payment.observability.ApproveOutcome;
import com.iseonjwa.payment.observability.PaymentMetrics;
import com.iseonjwa.payment.payment.domain.DeclineReason;
import com.iseonjwa.payment.payment.domain.IdempotencyRecord;
import com.iseonjwa.payment.payment.domain.IdempotencyRecordRepository;
import com.iseonjwa.payment.payment.domain.Payment;
import com.iseonjwa.payment.payment.domain.PaymentMethod;
import com.iseonjwa.payment.payment.domain.PaymentRepository;
import com.iseonjwa.payment.payment.domain.PaymentStatus;
import com.iseonjwa.payment.payment.domain.PgProvider;
import com.iseonjwa.payment.pg.application.ApproveCommand;
import com.iseonjwa.payment.pg.application.PgApprovalGate;
import com.iseonjwa.payment.pg.application.PgCheckoutProfile;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgClients;
import com.iseonjwa.payment.pg.application.PgIdempotencyKey;
import com.iseonjwa.payment.pg.application.PgOrderId;
import com.iseonjwa.payment.pg.application.PgPaymentRef;
import com.iseonjwa.payment.pg.application.PgPermit;
import com.iseonjwa.payment.pg.application.PgUnavailableException;
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
 * <p>Note: 신규 승인 시도만 6단계에서 {@code pg-approve} 허가(Bulkhead·Circuit Breaker)를 얻는다. 같은 키·기존 결제
 * 응답은 허가 없이 나가서 CB가 OPEN이어도 그대로 응답한다. 허가는 TX1 앞에서 얻어, 못 얻으면 행도 멱등 기록도 남지
 * 않는다(0018). 정상 경로에서는 PG 호출 직후 {@link #proceed}가 정산하고, 그 밖의 어떤 경로로 끝나도
 * try-with-resources가 호출 결과로 세지 않고 반환한다(안전망).
 *
 * <p>Note: 승인 결과 지표는 {@link #approve}의 finally 한 곳에서 호출마다 정확히 한 번 기록한다. 흐름의 각 갈래는 결과 종류만
 * {@link Recording}에 남기고, 예외로 끝나면 예외 종류로 정한다. 어느 갈래도 종류를 남기지 못하고 반환하면 {@code error}로
 * 세어 빠진 경로가 조용히 사라지지 않게 한다.
 */
@Service
public class PaymentApprovalService {
    private static final Logger log = LoggerFactory.getLogger(PaymentApprovalService.class);
    // 유니크 위반 뒤 다시 조회하는 횟수. 한 번이면 충분하고 계속 겹치면 일시 장애로 본다
    private static final int MAX_ATTEMPTS = 2;
    private static final String RESERVATION_UNIQUE = "uq_payments_reservation_id";
    private static final String IDEMPOTENCY_PK = "idempotency_records_pkey";
    // 예매 하나에 토큰이 같으면 주문 식별자도 같아 같은 요청의 충돌에서 이 제약이 먼저 걸릴 수도 있다
    private static final String PG_ORDER_UNIQUE = "uq_payments_pg_order_id";
    private static final String PG_TX_UNIQUE = "uq_payments_pg_tx_id";

    private final PaymentTokenVerifier tokens;
    private final PaymentRepository payments;
    private final IdempotencyRecordRepository idempotency;
    private final PaymentTx tx;
    private final PgClients pgClients;
    private final PgApprovalGate approvalGate;
    private final PgCheckoutProfile pgProfile;
    private final PaymentMetrics metrics;
    private final ObjectMapper mapper;
    private final Clock clock;

    public PaymentApprovalService(PaymentTokenVerifier tokens, PaymentRepository payments,
            IdempotencyRecordRepository idempotency, PaymentTx tx, PgClients pgClients, PgApprovalGate approvalGate,
            PgCheckoutProfile pgProfile, PaymentMetrics metrics, ObjectMapper mapper, @Qualifier("commonAuditingClock") Clock clock) {
        this.tokens = tokens;
        this.payments = payments;
        this.idempotency = idempotency;
        this.tx = tx;
        this.pgClients = pgClients;
        this.approvalGate = approvalGate;
        this.pgProfile = pgProfile;
        this.metrics = metrics;
        this.mapper = mapper;
        this.clock = clock;
    }

    public PaymentOutcome approve(ApprovePaymentCommand command) {
        var recording = new Recording();
        var started = System.nanoTime();
        try {
            return approveChecked(command, recording);
        } catch (BusinessException exception) {
            recording.failed(exception);
            throw exception;
        } catch (RuntimeException | Error exception) {
            recording.set(ApproveOutcome.ERROR);
            throw exception;
        } finally {
            metrics.recordApprove(recording.outcome(), System.nanoTime() - started);
        }
    }

    private PaymentOutcome approveChecked(ApprovePaymentCommand command, Recording recording) {
        // 2단계: 길이 상한은 해시 계산과 DB 조회보다 먼저 본다
        if (command.paymentToken().length() > PaymentTokenVerifier.MAX_TOKEN_LENGTH) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_TOKEN_INVALID);
        }
        try {
            return approveFlow(command, recording);
        } catch (DataAccessException | TransactionException exception) {
            // PG를 부르기 전 구간의 DB 오류(연결 대기 초과 포함)다. 행이 만들어지지 않았거나 PG 호출 전이라 같은 키로 다시
            // 시도할 수 있다. PG 호출 뒤의 DB 오류는 settle이 잡아 202로 응답하므로 여기까지 오지 않는다
            log.warn("PG 호출 전 구간 DB 오류", exception);
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private PaymentOutcome approveFlow(ApprovePaymentCommand command, Recording recording) {
        var requestHash = PaymentRequestHash.of(command.paymentToken(), command.amount(), command.clientPaymentRef(),
                command.pgProvider());
        for (var attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            // 3단계: 토큰 검증보다 먼저 같은 키의 기록을 본다. 토큰이 이미 만료됐어도 재시도가 결과를 받게 한다(0017)
            var replayed = replayByIdempotencyKey(command, requestHash);
            if (replayed.isPresent()) {
                recording.set(ApproveOutcome.LINKED);
                return replayed.get();
            }

            // 4단계: 토큰·금액 검증
            var token = verifyToken(command);

            // 5단계: 같은 예매의 기존 결제
            var existing = payments.findByReservationId(token.reservationId());
            if (existing.isPresent()) return respondToExisting(command, token, requestHash, existing.get(), recording);
            if (token.isExpired(clock.instant())) {
                // 만료된 토큰은 조회 전용이다. 기존 결제가 없으면 신규 승인을 하지 않는다
                throw new BusinessException(PaymentErrorCode.PAYMENT_TOKEN_INVALID);
            }

            // 이 결제를 처리할 PG를 정한다. 허가·TX1 전에 거절해 행도 멱등 기록도 남기지 않는다
            var provider = decideProvider(command);
            var approved = tryNewApproval(command, token, requestHash, provider, recording);
            if (approved.isPresent()) return approved.get();
            // 같은 키·같은 예매의 동시 요청과 부딪혔다. 500이 아니라 정상 흐름이므로 처음부터 다시 조회한다(0017·0026)
            log.info("TX1 유니크 충돌 attempt={}", attempt);
        }
        throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    // 요청이 고른 PG(없으면 서버 기본)로 정하고, 이 서버가 처리할 수 없는 조합은 400으로 거절한다
    private PgProvider decideProvider(ApprovePaymentCommand command) {
        var server = pgProfile.provider();
        var effective = command.pgProvider() != null ? command.pgProvider() : server;
        // 토스를 켜지 않은 서버(Mock 모드)에는 결제창이 없어 토스로 처리할 수 없다
        if (effective == PgProvider.TOSS && server != PgProvider.TOSS) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        // 토스 모드의 Mock 건너뛰기는 설정으로 끌 수 있다
        if (command.pgProvider() == PgProvider.MOCK && server == PgProvider.TOSS && !pgProfile.mockBypassEnabled()) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        // 토스는 결제창에서 받은 paymentKey 없이 승인할 수 없다
        if (effective == PgProvider.TOSS && command.clientPaymentRef() == null) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        return effective;
    }

    // 6~10단계: 허가 → TX1 → PG → TX2. TX1이 유니크 충돌이면 비어 있고 호출한 쪽이 다시 조회한다
    private Optional<PaymentOutcome> tryNewApproval(ApprovePaymentCommand command, PaymentToken token,
            String requestHash, PgProvider provider, Recording recording) {
        // 6단계: 신규 승인 허가. 못 얻으면 행도 멱등 기록도 만들지 않고 503이다
        try (var permit = acquirePermit()) {
            // 7단계: TX1. 아래 try는 TX1만 감싼다. PG 호출·TX2의 예외가 여기로 들어오면 안 된다
            Payment payment;
            try {
                // 토스 모드에서만 paymentKey를 pg_tx_id에 먼저 저장한다. Mock은 clientPaymentRef를 무시한다
                // 토스로 처리할 때만 paymentKey를 pg_tx_id에 먼저 저장한다. Mock은 clientPaymentRef를 무시한다
                var pgTxId = provider == PgProvider.TOSS ? command.clientPaymentRef() : null;
                payment = tx.createRequested(command, token, requestHash, provider, pgTxId);
            } catch (DataIntegrityViolationException exception) {
                if (isConflictOn(exception, PG_TX_UNIQUE) && !isExpectedConflict(exception)) {
                    // 같은 paymentKey가 다른 결제에 이미 연결돼 있다. 일시 장애(503)가 아니라 요청 충돌이다
                    throw new BusinessException(PaymentErrorCode.PAYMENT_ALREADY_EXISTS);
                }
                if (!isExpectedConflict(exception)) throw exception;
                return Optional.empty();
            }
            return Optional.of(proceed(payment, command, token, permit, recording));
        }
    }

    private PgPermit acquirePermit() {
        try {
            return approvalGate.acquire();
        } catch (PgUnavailableException exception) {
            // CB가 OPEN인 동안 거절마다 남으면 장애 순간에 로그가 넘친다. 건수는 payment.approve{outcome=unavailable}가 본다
            log.debug("PG 승인 허가 거절: {}", exception.getMessage());
            throw new BusinessException(PaymentErrorCode.PG_UNAVAILABLE);
        }
    }

    // 8~10단계: 만료 재검사, PG 호출, TX2
    private PaymentOutcome proceed(Payment payment, ApprovePaymentCommand command, PaymentToken token,
            PgPermit permit, Recording recording) {
        if (token.isExpired(clock.instant())) {
            // PG를 부르지 않았으므로 미전송이 확실하다. 토큰이 TX1과 PG 호출 사이에 만료된 경우다(0018).
            // 호출 결과로 세지 않고 허가를 돌려준다
            permit.release();
            recording.set(ApproveOutcome.DECLINED);
            return settle(payment, () -> tx.settleDeclined(payment, command, DeclineReason.TOKEN_EXPIRED));
        }
        permit.callStarted();
        var result = callPg(payment, command);
        // 허가는 PG 호출이 끝난 즉시 정산한다. TX2가 PG 호출 용량을 붙잡으면 안 된다. 분류는 PgPermit.settle이 맡는다
        permit.settle(result);
        recording.set(switch (result) {
            case PgClient.Approved approved -> ApproveOutcome.APPROVED;
            case PgClient.Declined declined -> ApproveOutcome.DECLINED;
            case PgClient.Unknown unknown -> ApproveOutcome.UNKNOWN;
        });
        if (result instanceof PgClient.Unknown unknown && unknown.cause() == UnknownCause.CLIENT_ERROR) {
            // 요청이 PG에 거절됐다. CB에는 세지 않지만 우리 쪽 설정·코드 문제일 수 있어 알린다(INV-8)
            log.error("PG가 승인 요청을 거절했다(CLIENT_ERROR) paymentId={}", payment.getId());
        }
        return switch (result) {
            case PgClient.Approved approved -> settle(payment, () -> tx.settleApproved(payment, command, approved));
            case PgClient.Declined declined ->
                    settle(payment, () -> tx.settleDeclined(payment, command, declined.reason()));
            // 결과를 모르면 거절로 닫지 않는다. 재확인 스케줄러가 PG를 조회해 확정한다(INV-8)
            case PgClient.Unknown unknown -> settle(payment, () -> tx.settleUnknown(payment, command, unknown.cause()));
        };
    }

    // 포트는 결과를 예외가 아닌 타입으로 돌려주지만, 예외가 나오면 호출한 쪽이 결과를 모르는 것(Unknown)으로 다룬다
    private PgClient.ApproveResult callPg(Payment payment, ApprovePaymentCommand request) {
        var orderId = new PgOrderId(payment.getPgOrderId());
        var clientPaymentRef = request.clientPaymentRef() == null ? null : new PgPaymentRef(request.clientPaymentRef());
        // 로컬 객체 생성은 PG 호출이 아니므로 try 밖이다. 여기서 나는 예외를 PG 장애로 세지 않는다
        var command = new ApproveCommand(orderId, payment.getAmount(), payment.getCurrency(),
                PgIdempotencyKey.ofApproval(orderId), clientPaymentRef);
        try {
            return pgClients.of(payment.getPgProvider()).approve(command);
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
            Payment existing, Recording recording) {
        if (existing.getStatus() == PaymentStatus.DECLINED || existing.getStatus() == PaymentStatus.REFUNDED) {
            // 종료된 결제는 연결하지 않고 기록도 남기지 않는다(0016)
            recording.set(ApproveOutcome.REJECTED);
            return PaymentOutcome.rejected(PaymentErrorCode.PAYMENT_ALREADY_CLOSED);
        }
        if (!sameCoreInformation(command, token, existing)) {
            // 다른 사용자나 다른 조건의 결제를 돌려주지 않는다. Location도 주지 않아 존재를 드러내지 않는다
            recording.set(ApproveOutcome.REJECTED);
            return PaymentOutcome.rejected(PaymentErrorCode.PAYMENT_ALREADY_EXISTS);
        }
        if (tx.linkAlias(command, requestHash, existing)) {
            log.info("event=PAYMENT_LINKED_BY_NEW_KEY paymentId={} userId={} idemKey={}", existing.getId(),
                    command.userId(), command.idempotencyKey());
        } else {
            // 같은 새 키가 동시에 와서 다른 요청이 먼저 저장했다. 다시 읽어 같은 본문인지 확인한다(0019)
            replayByIdempotencyKey(command, requestHash);
        }
        recording.set(ApproveOutcome.LINKED);
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
        return isConflictOn(exception, RESERVATION_UNIQUE) || isConflictOn(exception, IDEMPOTENCY_PK)
                || isConflictOn(exception, PG_ORDER_UNIQUE);
    }

    private static boolean isConflictOn(DataIntegrityViolationException exception, String constraint) {
        return String.valueOf(exception.getMostSpecificCause().getMessage()).contains(constraint);
    }

    /**
     * 승인 요청 하나의 결과 종류를 흐름 곳곳에서 받아 {@link #approve}의 finally가 한 번에 기록하게 한다.
     * 아무도 종류를 남기지 않고 반환하면 {@code error}다.
     */
    private static final class Recording {
        private ApproveOutcome outcome = ApproveOutcome.ERROR;

        void set(ApproveOutcome outcome) {
            this.outcome = outcome;
        }

        // 4xx는 요청 거절, 허가 거절 503은 unavailable, 그 밖의 5xx는 error다
        void failed(BusinessException exception) {
            var code = exception.getErrorCode();
            if (code == PaymentErrorCode.PG_UNAVAILABLE) outcome = ApproveOutcome.UNAVAILABLE;
            else if (code.status() < 500) outcome = ApproveOutcome.REJECTED;
            else outcome = ApproveOutcome.ERROR;
        }

        ApproveOutcome outcome() {
            return outcome;
        }
    }
}
