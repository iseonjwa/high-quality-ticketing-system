package com.iseonjwa.reservation.queue.presentation;

import com.iseonjwa.common.response.FailureResponse;
import com.iseonjwa.reservation.queue.application.QueueErrorCode;
import com.iseonjwa.reservation.queue.application.QueueProperties;
import com.iseonjwa.reservation.queue.domain.QueueStorageUnavailableException;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = QueueController.class)
@Order(Ordered.HIGHEST_PRECEDENCE) // 없으면 예외가 common 예외 처리기로 감
@RequiredArgsConstructor
public class QueueExceptionHandler {

    private final QueueProperties queueProperties;

    @ExceptionHandler(QueueStorageUnavailableException.class)
    public ResponseEntity<FailureResponse> handleQueueStorageUnavailableException(QueueStorageUnavailableException e){
        Long retryAfterSeconds = e.retryAfter()
                .map(retryAfter -> retryAfter.toSeconds())
                .orElse(queueProperties.pollMin().toSeconds());

        return ResponseEntity.status(QueueErrorCode.QUEUE_TEMPORARILY_UNAVAILABLE.status())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                .contentType(MediaType.APPLICATION_JSON)
                .body(FailureResponse.of(QueueErrorCode.QUEUE_TEMPORARILY_UNAVAILABLE, null, MDC.get("traceId")));
    }
}
