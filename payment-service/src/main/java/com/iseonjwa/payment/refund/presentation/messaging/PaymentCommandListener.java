package com.iseonjwa.payment.refund.presentation.messaging;

import com.iseonjwa.payment.payment.application.BillingPaymentService;
import com.iseonjwa.payment.refund.application.RefundRequestService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * {@code payment.command}를 구독한다. 같은 토픽에 두 이벤트가 온다(D6 기준선): {@code RefundRequested}는 환불을 영속화하고
 * PG를 부르지 않는다. {@code BillingPaymentRequested}(추첨 자동결제, ISE-89)는 빌링키로 결제를 승인한다. 그 밖의 eventType은
 * 환불 파서가 영구 오류로 보고 DLQ로 보낸다.
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
    private final BillingPaymentRequestedMessageParser billingParser;
    private final BillingPaymentService billingPayments;

    PaymentCommandListener(RefundRequestedMessageParser parser, RefundRequestService service,
            BillingPaymentRequestedMessageParser billingParser, BillingPaymentService billingPayments) {
        this.parser = parser;
        this.service = service;
        this.billingParser = billingParser;
        this.billingPayments = billingPayments;
    }

    // Note: 자동결제는 허가(pg-approve)를 못 얻으면 PgUnavailableException을 던진다. 결제 행을 만들기 전이라 오류 처리기가
    // DLQ로 보내지 않고 같은 레코드를 계속 다시 읽는다(백프레셔, PaymentCommandErrorHandlers)
    @KafkaListener(topics = TOPIC, groupId = GROUP, containerFactory = CONTAINER_FACTORY)
    public void onMessage(String value) {
        var billing = billingParser.parseIfBilling(value);
        if (billing.isPresent()) {
            billingPayments.approve(billing.get());
            return;
        }
        var message = parser.parse(value);
        service.handle(message.eventId(), message.payload());
    }
}
