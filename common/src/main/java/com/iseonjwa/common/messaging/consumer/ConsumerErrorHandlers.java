package com.iseonjwa.common.messaging.consumer;

import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.Map;

/**
 * 이벤트 소비 리스너의 오류 처리기를 만든다. 빈으로 자동 등록하지 않는다. 서비스가 자기 {@code @Configuration}에서 리스너별
 * 컨테이너 팩토리를 만들 때 {@link ConsumerContainerFactories#recordAck}로 붙인다.
 *
 * <p>Note: 전역 {@code CommonErrorHandler} 빈으로 만들지 않는다. 리스너마다 오류 규칙이 다르다(일반 소비자는 재시도 뒤 DLQ,
 * DLQ 처리기는 건너뛰면 안 되므로 무제한 재시도). 전역 빈 하나가 모든 리스너에 적용되면 DLQ 처리기가 메시지를 건너뛸 수 있다.
 */
public final class ConsumerErrorHandlers {
    public static final String DEAD_LETTER_SUFFIX = ".dlq";
    // 1·2·4초 간격 3회 재시도(운영 값). 테스트는 BackOff를 받는 오버로드에 짧은 값을 넘긴다
    public static final long RETRY_INITIAL_INTERVAL_MILLIS = 1_000;
    public static final double RETRY_MULTIPLIER = 2.0;
    public static final int MAX_RETRIES = 3;
    // 무제한 재시도 처리기의 운영 값: 1초에서 시작해 2배씩, 최대 60초 간격
    public static final long UNLIMITED_INITIAL_INTERVAL_MILLIS = 1_000;
    public static final double UNLIMITED_MULTIPLIER = 2.0;
    public static final long UNLIMITED_MAX_INTERVAL_MILLIS = 60_000;

    private static final Logger log = LoggerFactory.getLogger(ConsumerErrorHandlers.class);

    private ConsumerErrorHandlers() {}

    /** 원본 토픽의 DLQ 토픽 이름이다. {@code payment.command}는 {@code payment.command.dlq}. */
    public static String deadLetterTopic(String topic) {
        return topic + DEAD_LETTER_SUFFIX;
    }

    /** 1초 시작, 2배, 3회 재시도(1·2·4초). */
    public static BackOff defaultRetryBackOff() {
        var backOff = new ExponentialBackOff(RETRY_INITIAL_INTERVAL_MILLIS, RETRY_MULTIPLIER);
        backOff.setMaxAttempts(MAX_RETRIES);
        return backOff;
    }

    public static DefaultErrorHandler retryThenDeadLetter(KafkaOperations<?, ?> template) {
        return retryThenDeadLetter(template, defaultRetryBackOff());
    }

    /**
     * 일시 오류는 {@code backOff}만큼 재시도한 뒤, {@link NonRetryableMessageException}은 재시도 없이 바로
     * {@code <원본토픽>.dlq}로 보낸다. DLQ 전송이 실패하면 복구가 실패해 원 메시지가 다시 전달되고 오프셋은 올라가지 않는다.
     */
    public static DefaultErrorHandler retryThenDeadLetter(KafkaOperations<?, ?> template, BackOff backOff) {
        // Note: 파티션을 지정하지 않는다(음수 파티션은 키 기반 배정). DLQ 토픽의 파티션 수가 원본과 같다는 보장이 없고,
        // 원본 파티션 번호를 그대로 쓰면 더 적은 DLQ 토픽에서 전송이 실패한다. 키는 원본 그대로라 같은 키는 같은 파티션이다.
        var publisher = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(deadLetterTopic(record.topic()), -1));
        // Note: 전송 결과를 기다리고 실패하면 예외를 던진다(기본값이지만 오프셋 보존이 이 설정에 달려 있어 명시한다).
        // 복구가 실패하면 원 메시지가 다시 전달되고 오프셋이 커밋되지 않아, DLQ 전송에 실패한 메시지를 잃지 않는다.
        publisher.setFailIfSendResultIsError(true);
        // 로그는 전송이 성공한 뒤에만 남긴다. publisher가 실패하면 예외가 그대로 올라가 로그에 이르지 않는다
        var handler = new DefaultErrorHandler((record, exception) -> {
            publisher.accept(record, exception);
            log.error("event=MESSAGE_DEAD_LETTERED topic={} partition={} offset={} key={} dlqTopic={} cause={}",
                    record.topic(), record.partition(), record.offset(), record.key(), deadLetterTopic(record.topic()),
                    causeName(exception));
        }, backOff);
        handler.addNotRetryableExceptions(NonRetryableMessageException.class);
        return handler;
    }

    /** 횟수와 경과 시간에 제한이 없는 지수 백오프(1초 시작, 2배, 최대 60초). */
    public static BackOff defaultUnlimitedBackOff() {
        return unlimitedBackOff(UNLIMITED_INITIAL_INTERVAL_MILLIS, UNLIMITED_MULTIPLIER, UNLIMITED_MAX_INTERVAL_MILLIS);
    }

    /**
     * 횟수와 경과 시간에 제한이 없는 지수 백오프.
     *
     * <p>Note: 최대 간격 60초는 {@code max.poll.interval.ms}(기본 5분)보다 작다. 재시도 대기 중에는 컨슈머가 poll하지
     * 못하는데, 한 번의 대기가 이 시간을 넘으면 컨슈머가 그룹에서 쫓겨나 리밸런스가 반복된다.
     */
    public static BackOff unlimitedBackOff(long initialIntervalMillis, double multiplier, long maxIntervalMillis) {
        var backOff = new ExponentialBackOff(initialIntervalMillis, multiplier);
        backOff.setMaxInterval(maxIntervalMillis);
        backOff.setMaxAttempts(Long.MAX_VALUE);
        backOff.setMaxElapsedTime(Long.MAX_VALUE);
        return backOff;
    }

    public static DefaultErrorHandler retryForever() {
        return retryForever(defaultUnlimitedBackOff());
    }

    /**
     * 복구기 없는 오류 처리기. 백오프가 끝나지 않으므로 복구(건너뛰기)에 이르지 않고, 원인이 풀릴 때까지 같은 레코드를 다시
     * 처리하며 오프셋을 커밋하지 않는다. DLQ 처리기처럼 건너뛰면 의무가 사라지는 소비자용이다.
     *
     * <p>Note: 모든 예외를 재시도 대상으로 둔다. {@code DefaultErrorHandler}는 기본으로 변환·역직렬화 예외 등을 재시도 없이
     * 곧바로 복구(기본 복구기는 로그만 남기고 건너뛴다)하는데, 이런 소비자에서는 어떤 예외로도 메시지를 건너뛰면 안 된다.
     */
    public static DefaultErrorHandler retryForever(BackOff backOff) {
        var handler = new DefaultErrorHandler(backOff);
        handler.setClassifications(Map.of(), true);
        return handler;
    }

    // 값 원문이 섞일 수 있는 예외 메시지는 남기지 않고 원인 클래스 이름만 남긴다
    private static String causeName(Exception exception) {
        var cause = exception instanceof ListenerExecutionFailedException && exception.getCause() != null
                ? exception.getCause() : exception;
        return cause.getClass().getName();
    }
}
