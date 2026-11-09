package com.iseonjwa.payment.refund.application;

import com.iseonjwa.common.messaging.inbox.ProcessedEventRepository;
import com.iseonjwa.payment.observability.PaymentAlertLog;
import com.iseonjwa.payment.payment.domain.StatusActor;
import com.iseonjwa.payment.refund.domain.DeadLetterRepository;
import com.iseonjwa.payment.refund.domain.DeadLetterResolution;
import com.iseonjwa.payment.refund.domain.NewDeadLetter;
import com.iseonjwa.payment.refund.domain.RefundSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code payment.command.dlq}로 온 환불 요청을 자동 복구 경로로 되돌린다(events-and-reliability.md 5절). 한 트랜잭션에서
 * {@code dead_letters}를 저장하고, 소비자와 같은 검증으로 환불을 만들고, 결과를 {@code dead_letters}에 반영한다.
 *
 * <p>내용 문제(검증 실패, 역직렬화 실패, 결제 상태)로는 예외를 던지지 않고 {@code OPEN}으로 기록한다. 예외는 DB·인프라 오류뿐이고,
 * 그때는 트랜잭션이 롤백되어 {@code dead_letters}도 환불도 {@code processed_events}도 남지 않는다.
 *
 * <p>Note: {@code processed_events}는 {@link com.iseonjwa.common.messaging.inbox.ProcessedEventService#processOnce}가 아니라
 * 저장소로 같은 트랜잭션에서 직접 쓴다. 중복 판단은 {@code dead_letters}의 {@code event_key} UNIQUE가 먼저 하고,
 * {@code processOnce}는 업무가 예외를 던지면 바깥 트랜잭션을 rollback-only로 만들기 때문이다. 이 기록은 소비 그룹별 처리
 * 사실을 남기는 용도다.
 */
@Service
public class DeadLetterService {
    public static final String CONSUMER_GROUP = "payment.command-dlq";
    private static final String PROCESSING = "처리 중";
    private static final String UNPARSABLE = "역직렬화·봉투 오류: ";
    private static final String EVENT_TYPE = "RefundRequested";
    private static final int REASON_LENGTH = 500;
    private static final int KEY_LENGTH = 100;

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    private final DeadLetterRepository deadLetters;
    private final RefundRequestService refundRequests;
    private final ProcessedEventRepository processedEvents;
    private final PaymentAlertLog alertLog;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public DeadLetterService(DeadLetterRepository deadLetters, RefundRequestService refundRequests,
            ProcessedEventRepository processedEvents, PaymentAlertLog alertLog, TransactionTemplate transaction,
            @Qualifier("commonAuditingClock") Clock clock) {
        this.deadLetters = deadLetters;
        this.refundRequests = refundRequests;
        this.processedEvents = processedEvents;
        this.alertLog = alertLog;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** 봉투를 읽지 못한 메시지. 환불을 만들 근거가 없어 {@code OPEN}으로 남긴다. */
    public void handleUnparsable(DeadLetterInput input, String failure) {
        var eventKey = positionKey(input);
        run(input, eventKey, null, null, UNPARSABLE + failure);
    }

    /** 봉투를 읽은 메시지. 검증을 통과하면 환불을 만들고, 아니면 {@code OPEN}으로 남긴다. */
    public void handleParsed(DeadLetterInput input, UUID eventId, RefundRequestedPayload payload) {
        run(input, eventId.toString(), eventId, payload, null);
    }

    // eventKey: 봉투를 읽었으면 eventId, 못 읽었으면 원본 위치(positionKey)
    private void run(DeadLetterInput input, String eventKey, UUID eventId, RefundRequestedPayload payload,
            String unparsableReason) {
        transaction.execute(status -> {
            var now = clock.instant();
            var id = deadLetters.insertOpen(new NewDeadLetter(truncate(input.sourceTopic(), KEY_LENGTH),
                    input.sourcePartition(), input.sourceOffset(), truncate(clean(input.messageKey()), KEY_LENGTH),
                    eventKey, eventId, eventId == null ? null : EVENT_TYPE, clean(input.payloadRaw()), PROCESSING), now);
            if (id.isEmpty()) {
                // 이미 처리한 DLQ 메시지(재전달)다. 아무것도 하지 않고 오프셋만 올라가게 한다
                log.info("이미 처리한 DLQ 메시지를 건너뜁니다 eventKey={}", eventKey);
                return Optional.<String>empty();
            }
            if (eventId != null) processedEvents.markProcessed(eventId, CONSUMER_GROUP, now);

            var opened = payload == null ? open(id.get(), unparsableReason)
                    : switch (refundRequests.settle(payload, RefundSource.DLQ, eventId, StatusActor.SYSTEM)) {
                        case RefundRequestOutcome.Rejected rejected -> open(id.get(), rejected.message());
                        case RefundRequestOutcome.Created created -> {
                            deadLetters.resolve(id.get(), DeadLetterResolution.AUTO_PERSISTED, created.refundId(), now);
                            yield Optional.<String>empty();
                        }
                        case RefundRequestOutcome.Duplicate duplicate -> {
                            deadLetters.resolve(id.get(), DeadLetterResolution.DUPLICATE, duplicate.refundId(), now);
                            yield Optional.<String>empty();
                        }
                    };
            // 이 트랜잭션이 커밋된 뒤에 건별 알림 줄이 찍힌다(PaymentAlertLog). 커밋이 실패하면 OPEN 기록도 알림도 남지 않는다.
            // 봉투를 못 읽었거나 결제를 못 읽으면 결제 정보는 '-'다
            opened.ifPresent(reason -> alertLog.deadLetterOpen(payload == null ? null : payload.paymentId(), eventKey,
                    reason));
            return opened;
        });
        // 즉시 알림은 PaymentDeadLetterOpen 규칙(지표)과 Loki 건별 규칙이 낸다. 건수와 최고령은 gauge-refresh가 센다
    }

    private Optional<String> open(Long id, String reason) {
        var cleaned = truncate(clean(reason), REASON_LENGTH);
        deadLetters.updateOpenReason(id, cleaned);
        return Optional.of(cleaned);
    }

    /**
     * 원본 메시지의 위치로 만든 event_key.
     *
     * <p>Note: DLQ 레코드의 위치가 아니라 원본 위치를 쓴다. 같은 원본 메시지가 DLQ에 두 번 들어와도(DLQ 전송은 성공했는데
     * 원본 오프셋 커밋이 실패해 원본이 다시 처리되고 다시 DLQ로 간다) 두 DLQ 레코드의 위치는 다르지만 원본 위치는 같아서
     * 한 건으로 묶인다.
     */
    private static String positionKey(DeadLetterInput input) {
        return truncate(input.sourceTopic(), KEY_LENGTH) + "-" + input.sourcePartition() + "-" + input.sourceOffset();
    }

    // PostgreSQL 텍스트는 NUL 문자를 저장하지 못한다. 내용 때문에 예외가 나면 같은 메시지를 영원히 다시 처리하게 되므로 바꿔 저장한다
    private static String clean(String value) {
        return value == null ? null : value.replace('\u0000', '�');
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        var end = Character.isHighSurrogate(value.charAt(max - 1)) ? max - 1 : max;
        return value.substring(0, end);
    }
}
