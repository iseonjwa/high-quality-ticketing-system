package com.iseonjwa.payment.refund.infrastructure.messaging;

import com.iseonjwa.payment.pg.application.PgUnavailableException;
import com.iseonjwa.payment.refund.application.NonRetryableMessageException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

/**
 * {@code payment.command} 리스너 전용 오류 처리(events-and-reliability.md 4절 3~5번). 전역 {@code CommonErrorHandler}
 * 빈으로 만들지 않고 이 리스너의 컨테이너 팩토리에만 붙인다. 다른 리스너와 DLQ 리스너(ISE-138)는 오류 규칙이 다르다.
 */
final class PaymentCommandErrorHandlers {
    static final String DLQ_TOPIC = "payment.command.dlq";
    // 1·2·4초 간격 3회 재시도(운영 값). 테스트는 create에 짧은 BackOff를 넘긴다
    static final long RETRY_INITIAL_INTERVAL_MILLIS = 1_000;
    static final double RETRY_MULTIPLIER = 2.0;
    static final int MAX_RETRIES = 3;
    // 자동결제 허가 대기 간격. 허가를 못 얻은 동안은 횟수 제한 없이 같은 레코드를 다시 읽는다(D6 백프레셔, ISE-89)
    static final long PG_PERMIT_WAIT_MILLIS = 1_000;

    private PaymentCommandErrorHandlers() {}

    static BackOff productionBackOff() {
        var backOff = new ExponentialBackOff(RETRY_INITIAL_INTERVAL_MILLIS, RETRY_MULTIPLIER);
        backOff.setMaxAttempts(MAX_RETRIES);
        return backOff;
    }

    static DefaultErrorHandler create(KafkaOperations<?, ?> template, BackOff backOff) {
        // Note: 파티션을 지정하지 않는다(음수 파티션은 키 기반 배정). DLQ 토픽의 파티션 수가 원본과 같다는 보장이 없고,
        // 원본 파티션 번호를 그대로 쓰면 더 적은 DLQ 토픽에서 전송이 실패한다. 키는 원본 그대로라 같은 예매는 같은 파티션이다.
        var recoverer = new DeadLetterPublishingRecoverer(template, (record, exception) -> new TopicPartition(DLQ_TOPIC, -1));
        // Note: 전송 결과를 기다리고 실패하면 예외를 던진다(기본값이지만 오프셋 보존이 이 설정에 달려 있어 명시한다).
        // 복구가 실패하면 원 메시지가 다시 전달되고 오프셋이 커밋되지 않아, DLQ 전송에 실패한 메시지를 잃지 않는다.
        recoverer.setFailIfSendResultIsError(true);
        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(NonRetryableMessageException.class);
        // Note: 허가 거절은 PG 장애·포화가 풀리면 성공하는 일시 상태이고, 결제 행을 만들기 전이라 다시 해도 안전하다.
        // 일반 재시도(3회 뒤 DLQ)를 쓰면 당첨 결제가 DLQ로 빠져 유실처럼 보이므로 이 예외만 무제한으로 기다린다.
        // 오프셋을 커밋하지 않으므로 키 순서도 유지된다. 대기 중에는 같은 파티션의 뒤 메시지(환불 포함)도 멈춘다
        handler.setBackOffFunction((record, exception) -> isPgUnavailable(exception)
                ? new FixedBackOff(PG_PERMIT_WAIT_MILLIS, FixedBackOff.UNLIMITED_ATTEMPTS) : null);
        return handler;
    }

    private static boolean isPgUnavailable(Throwable exception) {
        for (var cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof PgUnavailableException) return true;
        }
        return false;
    }
}
