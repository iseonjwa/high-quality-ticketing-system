package com.iseonjwa.payment.payment.application;

import com.iseonjwa.common.messaging.outbox.OutboxWriter;
import com.iseonjwa.payment.payment.domain.DeclineReason;
import com.iseonjwa.payment.payment.domain.Payment;
import com.iseonjwa.payment.payment.domain.PaymentApprovedEvent;
import com.iseonjwa.payment.payment.domain.PaymentDeclinedEvent;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 결제 결과를 {@code payment.result} 토픽의 Outbox 행으로 쓴다(SA 10-1). 반드시 상태 전이와 같은 트랜잭션에서,
 * 조건부 UPDATE의 영향 행이 1일 때만 호출한다(INV-5). 트랜잭션이 없으면 {@link OutboxWriter}가 거절한다.
 *
 * <p>Note: 전이로 바뀐 값(승인 시각, 거절 사유)은 엔티티에서 다시 읽지 않고 인자로 받는다. 읽어 오는 값에 기대면
 * 저장소 쪽 영속성 컨텍스트 처리가 바뀔 때 이벤트가 조용히 어긋난다. 바뀌지 않는 값(예매·사용자·금액)만 엔티티에서 읽는다.
 */
@Service
public class PaymentResultPublisher {
    static final String TOPIC = "payment.result";

    private final OutboxWriter outbox;

    public PaymentResultPublisher(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    public void publishApproved(Payment payment, Instant pgApprovedAt) {
        // SA 10-2의 예시처럼 밀리초 정밀도로 내보낸다
        var payload = new PaymentApprovedEvent(payment.getId(), payment.getReservationId(), payment.getUserId(),
                payment.getAmount(), payment.getCurrency(), pgApprovedAt.truncatedTo(ChronoUnit.MILLIS));
        outbox.write(TOPIC, key(payment), "PaymentApproved", payload);
    }

    public void publishDeclined(Payment payment, DeclineReason reason) {
        var payload = new PaymentDeclinedEvent(payment.getId(), payment.getReservationId(), payment.getUserId(),
                reason.name());
        outbox.write(TOPIC, key(payment), "PaymentDeclined", payload);
    }

    // 파티션 키는 reservationId다. 같은 예매의 결제 이벤트는 순서가 유지된다
    private static String key(Payment payment) {
        return String.valueOf(payment.getReservationId());
    }
}
