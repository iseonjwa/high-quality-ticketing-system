package com.iseonjwa.reservation.reservation.presentation.messaging;

import com.iseonjwa.reservation.reservation.application.PaymentResultService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * {@code payment.result}(PaymentApproved·PaymentDeclined·PaymentRefunded)를 구독한다(SA 10-1).
 *
 * <p>Note: 값은 문자열로 받아 여기서 봉투를 읽는다. 깨진 JSON도 역직렬화 단계에서 실패하지 않고 파서의 영구 오류가 되어
 * 원문 그대로 DLQ로 간다(결제 서비스 PaymentCommandListener와 같은 방식).
 */
@Component
@RequiredArgsConstructor
public class PaymentResultListener {
    public static final String TOPIC = "payment.result";
    public static final String CONTAINER_FACTORY = "paymentResultListenerFactory";

    private final PaymentResultMessageParser parser;
    private final PaymentResultService service;

    @KafkaListener(topics = TOPIC, groupId = PaymentResultService.CONSUMER_GROUP, containerFactory = CONTAINER_FACTORY)
    public void onMessage(String value) {
        service.handle(parser.parse(value));
    }
}
