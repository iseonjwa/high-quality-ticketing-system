package com.iseonjwa.reservation.reservation.application;

import com.iseonjwa.reservation.reservation.domain.entity.DeadLetter;
import com.iseonjwa.reservation.reservation.domain.entity.DeadLetterMessage;
import com.iseonjwa.reservation.reservation.domain.repository.DeadLetterRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;

/**
 * {@code payment.result.dlq}로 온 결제 결과를 한 번 더 처리하고, 결과를 {@code dead_letters}에 남긴다(ISE-74, ADR 0022).
 *
 * <ul>
 *   <li>봉투를 읽지 못한 메시지: 다시 처리할 근거가 없어 OPEN으로 남기고 알린다.</li>
 *   <li>읽은 메시지: {@link PaymentResultService#handle}로 다시 처리한다. 성공하면 RESOLVED(REPLAYED), 실패하면 OPEN으로 남기고 알린다.</li>
 * </ul>
 *
 * <p>Note: DLQ에 올 즈음(1·2·4초 재시도 뒤)에는 동시 전이 같은 일시 문제가 풀려 있는 경우가 많아 한 번 더 처리한다. 다시 처리는
 * {@code processed_events}로 멱등이라, 이미 반영된 이벤트면 아무것도 바꾸지 않고 RESOLVED가 된다.
 *
 * <p>Note: DB에 닿지 못한 인프라 오류는 OPEN으로 기록하지 않고 그대로 던진다. 컨테이너가 같은 레코드를 계속 다시 처리하고
 * ({@code retryForever}) DB가 돌아오면 이어서 처리한다. 기록해 버리면 승인 건이 OPEN으로 끝나 예매가 만료되고, 돈은 받았는데
 * 티켓도 환불 요청도 없는 상태가 된다. DB가 꺼졌을 때 트랜잭션 시작 단계에서 나는 예외는 {@code DataAccessException}이 아닌
 * {@link CannotCreateTransactionException}이라 따로 본다(#282 리뷰).
 */
@Service
@RequiredArgsConstructor
public class PaymentResultDlqService {
    public static final String CONSUMER_GROUP = "reservation.payment-result-dlq";
    private static final String UNPARSABLE = "역직렬화·봉투 오류: ";
    private static final String REPLAY_FAILED = "다시 처리 실패: ";
    private static final int REASON_LENGTH = 500;

    private static final Logger log = LoggerFactory.getLogger(PaymentResultDlqService.class);

    private final PaymentResultService paymentResults;
    private final DeadLetterRepository deadLetters;
    private final Clock clock;

    // 봉투를 읽지 못한 메시지. 멱등 키는 원본 위치다
    public void recordUnparsable(DeadLetterMessage message, String failure) {
        var deadLetter = DeadLetter.open(message, message.positionKey(), null, reason(UNPARSABLE + failure),
                clock.instant());
        if (deadLetters.saveOpenIfAbsent(deadLetter)) {
            log.error("event=DEAD_LETTER_OPEN topic={} eventKey={} reason={}",
                    deadLetter.getSourceTopic(), deadLetter.getEventKey(), deadLetter.getReason());
        } else {
            log.info("이미 기록한 DLQ 메시지를 건너뜁니다. eventKey={}", deadLetter.getEventKey());
        }
    }

    // 읽은 메시지. 멱등 키는 eventId다
    public void replay(DeadLetterMessage message, PaymentResult result) {
        var eventKey = result.getEventId().toString();
        try {
            paymentResults.handle(result);
        } catch (RuntimeException failure) {
            if (isInfrastructure(failure)) {
                throw failure;
            }
            var reason = reason(REPLAY_FAILED + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            var deadLetter = DeadLetter.open(message, eventKey, result.getEventId(), reason, clock.instant());
            if (deadLetters.saveOpenIfAbsent(deadLetter)) {
                log.error("event=DEAD_LETTER_OPEN topic={} eventKey={} reservationId={} paymentId={} reason={}",
                        deadLetter.getSourceTopic(), eventKey, result.getReservationId(), result.getPaymentId(),
                        deadLetter.getReason());
            } else {
                log.info("이미 기록한 DLQ 메시지를 건너뜁니다. eventKey={}", eventKey);
            }
            return;
        }
        if (deadLetters.saveResolved(DeadLetter.replayed(message, eventKey, result.getEventId(), clock.instant()))) {
            log.info("DLQ의 결제 결과를 다시 처리했습니다. eventId={}, reservationId={}, paymentId={}",
                    result.getEventId(), result.getReservationId(), result.getPaymentId());
        }
    }

    // 원인 사슬까지 보고 DB에 닿지 못한 오류인지 가린다
    static boolean isInfrastructure(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TransientDataAccessException || cause instanceof DataAccessResourceFailureException
                    || cause instanceof CannotCreateTransactionException
                    || cause instanceof SQLTransientConnectionException || cause instanceof SQLRecoverableException) {
                return true;
            }
        }
        return false;
    }

    // 오류 메시지에 원문 값(NUL 포함)이 섞일 수 있어 치환하고 컬럼 길이에 맞춰 자른다
    private static String reason(String text) {
        return DeadLetterMessage.truncate(DeadLetterMessage.clean(text), REASON_LENGTH);
    }
}
