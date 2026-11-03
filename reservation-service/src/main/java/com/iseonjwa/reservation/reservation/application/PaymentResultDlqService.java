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
 * <p>Note: DB 연결이 끊긴 것 같은 인프라 오류는 기록할 수도 없으니 그대로 던진다. 컨테이너가 같은 레코드를 계속 다시 처리하고
 * ({@code retryForever}) DB가 돌아오면 이어서 처리한다.
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
        var eventKey = message.positionKey();
        open(DeadLetter.open(message, eventKey, null, reason(UNPARSABLE + failure), clock.instant()));
    }

    // 읽은 메시지. 멱등 키는 eventId다
    public void replay(DeadLetterMessage message, PaymentResult result) {
        var eventKey = result.getEventId().toString();
        try {
            paymentResults.handle(result);
        } catch (TransientDataAccessException | DataAccessResourceFailureException infrastructure) {
            throw infrastructure;
        } catch (RuntimeException failure) {
            var reason = reason(REPLAY_FAILED + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            open(DeadLetter.open(message, eventKey, result.getEventId(), reason, clock.instant()));
            return;
        }
        if (deadLetters.saveIfAbsent(DeadLetter.replayed(message, eventKey, result.getEventId(), clock.instant()))) {
            log.info("DLQ의 결제 결과를 다시 처리했습니다. eventId={}, reservationId={}",
                    result.getEventId(), result.getReservationId());
        }
    }

    // 커밋된 뒤에 알린다. 이미 있는 건(DLQ 재전달)이면 다시 알리지 않는다
    private void open(DeadLetter deadLetter) {
        if (deadLetters.saveIfAbsent(deadLetter)) {
            log.error("event=DEAD_LETTER_OPEN topic={} eventKey={} reason={}",
                    deadLetter.getSourceTopic(), deadLetter.getEventKey(), deadLetter.getReason());
        } else {
            log.info("이미 기록한 DLQ 메시지를 건너뜁니다. eventKey={}", deadLetter.getEventKey());
        }
    }

    private static String reason(String text) {
        return text.length() <= REASON_LENGTH ? text : text.substring(0, REASON_LENGTH);
    }
}
