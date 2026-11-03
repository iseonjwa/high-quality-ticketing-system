package com.iseonjwa.reservation.lottery.application;

import com.iseonjwa.common.messaging.outbox.OutboxWriter;
import org.springframework.stereotype.Service;

/**
 * 당첨 예매의 자동결제 요청을 {@code payment.command} 토픽의 Outbox 행으로 쓴다(SA 10-1·10-3, D6 기준선).
 * 반드시 배정 워커의 트랜잭션 안에서, 예매를 응모에 연결한 뒤 호출한다. 트랜잭션이 없으면 {@link OutboxWriter}가 거절한다.
 *
 * <p>Note: 키는 {@code reservationId}다. Payment가 같은 예매의 요청을 한 번만 처리하는 멱등 키이기도 해서, 릴레이가 재시작해
 * 같은 요청을 다시 보내도 결제는 한 건이다(ADR 0026, D12). 금액은 예매 원장 값이고 결과는 예매가 원장과 대조한다(ADR 0027).
 */
@Service
public class BillingPaymentRequestPublisher {
    static final String TOPIC = "payment.command";
    static final String EVENT_TYPE = "BillingPaymentRequested";
    private static final int VERSION = 1;
    private static final String CURRENCY = "KRW";

    private final OutboxWriter outbox;

    public BillingPaymentRequestPublisher(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    public void publish(long reservationId, long userId, long amount, long lotteryEntryId) {
        var payload = new BillingPaymentRequested(reservationId, userId, amount, CURRENCY, lotteryEntryId);
        outbox.write(TOPIC, String.valueOf(reservationId), EVENT_TYPE, VERSION, payload);
    }

    // SA 10-3 필드에 currency를 더했다. 결제 계약이 금액과 통화를 함께 대조하고, SA 10-4가 필드 추가를 허용한다
    record BillingPaymentRequested(long reservationId, long userId, long amount, String currency,
                                   long lotteryEntryId) {}
}
