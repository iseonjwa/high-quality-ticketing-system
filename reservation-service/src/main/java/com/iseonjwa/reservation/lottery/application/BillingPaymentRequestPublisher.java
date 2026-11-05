package com.iseonjwa.reservation.lottery.application;

import com.iseonjwa.common.messaging.outbox.OutboxWriter;
import org.springframework.stereotype.Service;

/**
 * 예매를 응모에 연결한 뒤 배정 워커의 트랜잭션 안에서 자동결제 요청을 {@code payment.command} Outbox 행으로 쓴다(SA 10-1·10-3, D6 기준선).
 *
 * <p>Note: 키 {@code reservationId}는 Payment의 멱등 키이기도 해서 릴레이가 같은 요청을 다시 보내도 결제는 한 건이다(ADR 0026, D12).
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
        BillingPaymentRequested payload = new BillingPaymentRequested(
                reservationId,
                userId,
                amount,
                CURRENCY,
                lotteryEntryId);

        outbox.write(TOPIC, String.valueOf(reservationId), EVENT_TYPE, VERSION, payload);
    }

    // 결제 계약이 금액과 통화를 함께 대조하므로 SA 10-3 필드에 currency를 더했다(SA 10-4가 필드 추가를 허용한다)
    record BillingPaymentRequested(long reservationId,
                                   long userId,
                                   long amount,
                                   String currency,
                                   long lotteryEntryId) {}
}
