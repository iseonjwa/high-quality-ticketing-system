package com.iseonjwa.reservation.seathold.infrastructure.messaging;

import com.iseonjwa.common.event.EventEnvelope;
import com.iseonjwa.common.messaging.outbox.OutboxEventSender;
import com.iseonjwa.reservation.seathold.application.SeatHoldDirectPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 접수형 선점 요청의 직접 발행(측정 전용, ISE-18). 봉투는 {@code OutboxWriter}와 같게 만들고, 전송은 Outbox 릴레이와 같은
 * {@link OutboxEventSender}(acks=all)로 한다. 소비자는 두 경로의 메시지를 구분하지 않는다.
 *
 * <p>Note: 브로커 확인을 {@code seat-hold.async.direct-publish-timeout}(기본 1초)까지 기다린다. 기다리는 동안 DB 커넥션은
 * 쥐지 않는다(커밋 뒤에 부른다). 시한을 넘겨도 전송을 취소하지 않으므로 늦게 브로커에 닿을 수 있다. 그때는 시한 정리보다
 * 먼저 처리되거나, 이미 REJECTED(TIMEOUT)인 요청이라 소비자가 건너뛴다
 */
@Component
public class KafkaSeatHoldDirectPublisher implements SeatHoldDirectPublisher {
    private static final Logger log = LoggerFactory.getLogger(KafkaSeatHoldDirectPublisher.class);
    static final String NAME = "seathold.direct_publish";

    private final OutboxEventSender sender;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String producer;
    private final Duration timeout;
    private final Counter sent;
    private final Counter failed;
    private final Timer ack;

    public KafkaSeatHoldDirectPublisher(OutboxEventSender sender, ObjectMapper mapper,
            @Qualifier("commonAuditingClock") Clock clock, @Value("${spring.application.name}") String producer,
            @Value("${seat-hold.async.direct-publish-timeout:1s}") Duration timeout, MeterRegistry registry) {
        this.sender = sender;
        this.mapper = mapper;
        this.clock = clock;
        this.producer = producer;
        this.timeout = timeout;
        this.sent = Counter.builder(NAME).description("직접 발행 결과 수").tag("result", "sent").register(registry);
        this.failed = Counter.builder(NAME).description("직접 발행 결과 수").tag("result", "failed").register(registry);
        this.ack = Timer.builder(NAME + ".ack").description("직접 발행 브로커 확인까지 걸린 시간")
                .publishPercentileHistogram().register(registry);
    }

    @Override
    public void publish(String topic, String messageKey, String eventType, int version, Object payload) {
        var envelope = new EventEnvelope<>(UUID.randomUUID(), eventType, version,
                clock.instant().truncatedTo(ChronoUnit.MILLIS), producer, MDC.get("traceId"), payload);
        var started = System.nanoTime();
        try {
            sender.send(topic, messageKey, mapper.writeValueAsString(envelope))
                    .get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            sent.increment();
        } catch (ExecutionException | TimeoutException | RuntimeException exception) {
            failed.increment();
            // 값 원문(페이로드)은 남기지 않는다
            log.warn("event=SEAT_HOLD_DIRECT_PUBLISH_FAILED eventId={} topic={} messageKey={} cause={}",
                    envelope.eventId(), topic, messageKey, exception.getClass().getName());
        } catch (InterruptedException exception) {
            failed.increment();
            Thread.currentThread().interrupt();
        } finally {
            ack.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }
}
