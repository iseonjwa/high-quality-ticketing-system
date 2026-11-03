package com.iseonjwa.common.messaging.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Outbox 릴레이의 지표(결제 observability.md 1절, ADR 0024). 서비스 구분은 이름이 아니라 공통 설정의
 * {@code management.metrics.tags.application} 태그가 한다. 라벨이 없으므로 카디널리티는 늘 낮다.
 *
 * <p>Note: 지표 레지스트리가 없는 서비스는 {@link #disabled()}로 아무것도 기록하지 않고 동작한다. 이 클래스만
 * Micrometer 타입을 가지므로, 릴레이는 레지스트리가 있는지 몰라도 된다.
 *
 * <p>Note: "마지막 성공"({@code outbox.relay.last.success.timestamp})은 라운드 반복이 예외 없이 끝난 시각이다. Kafka가 죽어
 * 전송이 실패해도 라운드는 정상으로 끝나므로 이 값은 계속 갱신된다. 즉 "릴레이 스레드가 살아서 돌고 있다"만 알려 준다. 브로커
 * 장애나 발행 지연은 {@code outbox.pending.oldest.seconds}(DB 집계 Gauge)가 잡는다. 값은 기동 시각으로 시작한다.
 */
public final class OutboxMetrics {
    private static final OutboxMetrics DISABLED = new OutboxMetrics();

    private final AtomicLong lastSuccess;
    private final Counter relayFailures;
    private final Counter eventsFailed;

    private OutboxMetrics() {
        this.lastSuccess = null;
        this.relayFailures = null;
        this.eventsFailed = null;
    }

    public OutboxMetrics(MeterRegistry registry, Clock clock) {
        this.lastSuccess = new AtomicLong(clock.instant().getEpochSecond());
        Gauge.builder("outbox.relay.last.success.timestamp", lastSuccess, AtomicLong::get)
                .description("릴레이 라운드 반복이 예외 없이 끝난 마지막 시각(epoch 초). 전송 실패는 포함하지 않는다")
                .register(registry);
        this.relayFailures = Counter.builder("outbox.relay.failures")
                .description("릴레이 주기가 예외로 끝난 수").register(registry);
        this.eventsFailed = Counter.builder("outbox.events.failed")
                .description("영구 오류로 FAILED가 된 Outbox 이벤트 수").register(registry);
    }

    /** 지표 없이 동작한다. */
    public static OutboxMetrics disabled() {
        return DISABLED;
    }

    void relaySucceeded(Instant at) {
        if (lastSuccess != null) lastSuccess.set(at.getEpochSecond());
    }

    void relayFailed() {
        if (relayFailures != null) relayFailures.increment();
    }

    void eventFailed() {
        if (eventsFailed != null) eventsFailed.increment();
    }
}
