package com.iseonjwa.reservation.seathold.presentation.messaging;

import com.iseonjwa.common.messaging.consumer.NonRetryableMessageException;
import com.iseonjwa.reservation.seathold.application.SeatHoldRequestProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * {@code seat-hold.request.dlq}를 구독해, 재시도 3회로도 처리하지 못한 요청을 {@code REJECTED(PROCESSING_FAILED)}로 닫는다(E15).
 *
 * <p>Note: 요청 ID를 읽을 수 없는 메시지는 오류 로그만 남기고 넘어간다. 그 요청은 30초 시한 정리가 TIMEOUT으로 닫는다.
 * 결제 결과 DLQ와 달리 원문을 따로 보관하지 않는다. 선점 요청은 사용자가 다시 시도하면 되고 돈이 걸리지 않는다.
 */
@Component
public class SeatHoldRequestDlqListener {
    public static final String TOPIC = SeatHoldRequestListener.TOPIC + ".dlq";
    public static final String CONSUMER_GROUP = "reservation.seat-hold-dlq";
    public static final String CONTAINER_FACTORY = "seatHoldRequestDlqListenerFactory";

    private static final Logger log = LoggerFactory.getLogger(SeatHoldRequestDlqListener.class);

    private final SeatHoldRequestedMessageParser parser;
    private final SeatHoldRequestProcessor processor;

    SeatHoldRequestDlqListener(SeatHoldRequestedMessageParser parser, SeatHoldRequestProcessor processor) {
        this.parser = parser;
        this.processor = processor;
    }

    @KafkaListener(topics = TOPIC, groupId = CONSUMER_GROUP, containerFactory = CONTAINER_FACTORY)
    public void onMessage(String value) {
        try {
            processor.rejectAsFailed(parser.requestId(value));
        } catch (NonRetryableMessageException unreadable) {
            log.error("event=SEAT_HOLD_DLQ_UNREADABLE 요청 ID를 읽을 수 없는 선점 DLQ 메시지를 건너뜁니다. reason={}",
                    unreadable.getMessage());
        }
    }
}
