package com.iseonjwa.common.messaging.consumer;

/**
 * 다시 처리해도 결과가 같은 메시지 오류다(검증 실패, 깨진 JSON, 봉투 형식 오류). {@link ConsumerErrorHandlers#retryThenDeadLetter}
 * 가 만든 오류 처리기는 이 예외를 재시도 없이 바로 DLQ로 보낸다. DB·네트워크처럼 다시 시도하면 풀릴 수 있는 오류에는 쓰지 않는다.
 */
public class NonRetryableMessageException extends RuntimeException {
    public NonRetryableMessageException(String reason) {
        super(reason);
    }

    public NonRetryableMessageException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
