package com.iseonjwa.payment.refund.infrastructure.messaging;

import com.iseonjwa.payment.refund.application.RefundRequestService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * {@code payment.command}를 구독해 환불 요청을 영속화한다. PG는 부르지 않는다.
 *
 * <p>Note: 값은 문자열로 받고 여기서 봉투를 읽는다. 값 역직렬화기가 {@code StringDeserializer}라 깨진 JSON도 역직렬화 단계에서
 * 실패하지 않고 파서의 영구 오류가 되어, 원문 문자열 그대로 DLQ로 간다. 그래서 {@code ErrorHandlingDeserializer}를 쓰지 않는다.
 */
@Component
public class PaymentCommandListener {
    public static final String TOPIC = "payment.command";
    public static final String GROUP = "payment.command";
    public static final String CONTAINER_FACTORY = "paymentCommandListenerFactory";

    private final RefundRequestedMessageParser parser;
    private final RefundRequestService service;

    PaymentCommandListener(RefundRequestedMessageParser parser, RefundRequestService service) {
        this.parser = parser;
        this.service = service;
    }

    @KafkaListener(topics = TOPIC, groupId = GROUP, containerFactory = CONTAINER_FACTORY)
    public void onMessage(String value) {
        var message = parser.parse(value);
        service.handle(message.eventId(), message.payload());
    }
}
