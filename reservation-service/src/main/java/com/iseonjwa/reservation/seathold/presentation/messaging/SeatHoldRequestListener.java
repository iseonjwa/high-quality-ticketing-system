package com.iseonjwa.reservation.seathold.presentation.messaging;

import com.iseonjwa.reservation.seathold.application.SeatHoldRequestProcessor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * {@code seat-hold.request}(SeatHoldRequested)를 구독해 접수형 선점을 처리한다(ISE-17).
 * {@code seat-hold.mode}와 상관없이 늘 켠다. 접수형으로 받은 요청이 남은 채 동기형으로 바꿔도 끝까지 처리해야 한다(ADR 0057).
 */
@Component
public class SeatHoldRequestListener {
    public static final String TOPIC = "seat-hold.request";
    public static final String CONTAINER_FACTORY = "seatHoldRequestListenerFactory";

    private final SeatHoldRequestedMessageParser parser;
    private final SeatHoldRequestProcessor processor;

    SeatHoldRequestListener(SeatHoldRequestedMessageParser parser, SeatHoldRequestProcessor processor) {
        this.parser = parser;
        this.processor = processor;
    }

    @KafkaListener(topics = TOPIC, groupId = SeatHoldRequestProcessor.CONSUMER_GROUP,
            containerFactory = CONTAINER_FACTORY)
    public void onMessage(String value) {
        processor.process(parser.parse(value));
    }
}
