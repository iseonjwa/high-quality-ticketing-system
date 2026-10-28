package com.iseonjwa.payment.event.application;

import com.iseonjwa.common.event.EventEnvelope;
import com.iseonjwa.payment.event.domain.OutboxEvent;
import com.iseonjwa.payment.event.domain.OutboxRepository;
import com.iseonjwa.payment.event.domain.PaymentApprovedEvent;
import com.iseonjwa.payment.event.domain.PaymentDeclinedEvent;
import com.iseonjwa.payment.payment.domain.Payment;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.UUID;

/**
 * 결제 결과를 {@code payment.result}로 내보낼 Outbox 행을 쓴다(SA 10-1). Kafka를 직접 부르지 않는다.
 *
 * <p>Note: 반드시 상태 전이와 같은 트랜잭션에서 호출한다({@code MANDATORY}). 별도 트랜잭션이면 결제는 커밋됐는데
 * 이벤트가 없거나, 이벤트만 남는 일이 생긴다(INV-5). 호출은 조건부 UPDATE의 영향 행이 1일 때만 한다.
 */
@Service
public class PaymentResultPublisher {
    public static final String TOPIC = "payment.result";
    static final String PRODUCER = "payment-service";
    private static final int VERSION = 1;

    private final OutboxRepository outbox;
    private final ObjectMapper mapper;
    private final Clock clock;

    public PaymentResultPublisher(OutboxRepository outbox, ObjectMapper mapper,
            @Qualifier("commonAuditingClock") Clock clock) {
        this.outbox = outbox;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publishApproved(Payment payment) {
        var payload = new PaymentApprovedEvent(payment.getId(), payment.getReservationId(), payment.getUserId(),
                payment.getAmount(), payment.getCurrency(), payment.getApprovedAt());
        record("PaymentApproved", payment, payload);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publishDeclined(Payment payment) {
        var payload = new PaymentDeclinedEvent(payment.getId(), payment.getReservationId(), payment.getUserId(),
                payment.getDeclineCode().name());
        record("PaymentDeclined", payment, payload);
    }

    private void record(String eventType, Payment payment, Object payload) {
        var now = clock.instant();
        var envelope = new EventEnvelope<>(UUID.randomUUID(), eventType, VERSION, now, PRODUCER,
                MDC.get("traceId"), payload);
        // 파티션 키는 reservationId다. 같은 예매의 이벤트는 순서가 유지된다
        outbox.save(OutboxEvent.pending(envelope.eventId(), TOPIC, String.valueOf(payment.getReservationId()),
                eventType, mapper.writeValueAsString(envelope), now));
    }
}
