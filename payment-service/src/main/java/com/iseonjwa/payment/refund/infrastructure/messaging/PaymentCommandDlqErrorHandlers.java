package com.iseonjwa.payment.refund.infrastructure.messaging;

import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.Map;

/**
 * {@code payment.command.dlq} 리스너 전용 오류 처리(events-and-reliability.md 5절). DLQ 처리기는 내용 문제로 예외를 던지지
 * 않으므로 여기에 오는 것은 DB·인프라 오류뿐이다. 영속화 전에는 메시지를 버리지 않으려고, 다른 토픽으로 보내지도 건너뛰지도
 * 않고 같은 레코드를 횟수 제한 없이 다시 처리한다.
 */
final class PaymentCommandDlqErrorHandlers {
    // 운영 값: 1초에서 시작해 2배씩, 최대 60초 간격
    static final long INITIAL_INTERVAL_MILLIS = 1_000;
    static final double MULTIPLIER = 2.0;
    static final long MAX_INTERVAL_MILLIS = 60_000;

    private PaymentCommandDlqErrorHandlers() {}

    static BackOff productionBackOff() {
        return unlimitedBackOff(INITIAL_INTERVAL_MILLIS, MULTIPLIER, MAX_INTERVAL_MILLIS);
    }

    /**
     * 횟수와 경과 시간에 제한이 없는 지수 백오프.
     *
     * <p>Note: 최대 간격 60초는 {@code max.poll.interval.ms}(기본 5분)보다 작다. 재시도 대기 중에는 컨슈머가 poll하지
     * 못하는데, 한 번의 대기가 이 시간을 넘으면 컨슈머가 그룹에서 쫓겨나 리밸런스가 반복된다.
     */
    static BackOff unlimitedBackOff(long initialIntervalMillis, double multiplier, long maxIntervalMillis) {
        var backOff = new ExponentialBackOff(initialIntervalMillis, multiplier);
        backOff.setMaxInterval(maxIntervalMillis);
        backOff.setMaxAttempts(Long.MAX_VALUE);
        backOff.setMaxElapsedTime(Long.MAX_VALUE);
        return backOff;
    }

    /**
     * 복구기 없는 오류 처리기. 백오프가 끝나지 않으므로 복구(건너뛰기)에 이르지 않고, DB가 돌아올 때까지 같은 레코드를 다시
     * 처리하며 오프셋을 커밋하지 않는다.
     *
     * <p>Note: 모든 예외를 재시도 대상으로 둔다. {@code DefaultErrorHandler}는 기본으로 변환·역직렬화 예외 등을 재시도 없이
     * 곧바로 복구(기본 복구기는 로그만 남기고 건너뛴다)하는데, 이 리스너에서는 어떤 예외로도 메시지를 건너뛰면 환불 의무가
     * 지워질 수 있다.
     */
    static DefaultErrorHandler create(BackOff backOff) {
        var handler = new DefaultErrorHandler(backOff);
        handler.setClassifications(Map.of(), true);
        return handler;
    }
}
