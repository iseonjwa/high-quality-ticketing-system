package com.iseonjwa.common.web;

import com.iseonjwa.common.response.FailureResponse;
import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.common.response.FieldError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.sql.SQLTransientConnectionException;

@RestControllerAdvice(basePackages = "com.iseonjwa")
public class CommonExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(CommonExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> handleBusinessException(BusinessException exception) {
        var error = exception.getErrorCode();
        return ResponseEntity.status(error.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(FailureResponse.of(error, null, MDC.get("traceId")));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var errors = exception.getBindingResult().getAllErrors().stream()
                .map(error -> new FieldError(error instanceof org.springframework.validation.FieldError fieldError
                        ? fieldError.getField() : error.getObjectName(), error.getDefaultMessage()))
                .toList();
        return new ResponseEntity<>(FailureResponse.of(CommonErrorCode.VALIDATION_FAILED,
                errors, MDC.get("traceId")), jsonHeaders(headers), status);
    }

    // 사용자 정보가 필요한 컨트롤러에 Gateway의 사용자 헤더가 없으면 인증 실패다.
    // 누락된 @RequestAttribute는 하위 클래스 없이 ServletRequestBindingException 자체로 던져진다.
    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(ServletRequestBindingException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (exception.getClass() == ServletRequestBindingException.class) {
            return new ResponseEntity<>(FailureResponse.of(CommonErrorCode.UNAUTHORIZED, null, MDC.get("traceId")),
                    jsonHeaders(headers), HttpStatus.UNAUTHORIZED);
        }
        return super.handleServletRequestBindingException(exception, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var error = switch (status.value()) {
            case 400 -> CommonErrorCode.VALIDATION_FAILED;
            case 401 -> CommonErrorCode.UNAUTHORIZED;
            case 403 -> CommonErrorCode.FORBIDDEN;
            case 404 -> CommonErrorCode.NOT_FOUND;
            case 405 -> CommonErrorCode.METHOD_NOT_ALLOWED;
            case 415 -> CommonErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case 429 -> CommonErrorCode.RATE_LIMITED;
            case 503 -> CommonErrorCode.SERVICE_UNAVAILABLE;
            default -> null;
        };
        var response = error != null ? FailureResponse.of(error, null, MDC.get("traceId")) : fallback(status);
        return new ResponseEntity<>(response, jsonHeaders(headers), status);
    }

    // Note: Content-Type을 미리 정하면 Spring이 요청 Accept로 형식을 고르는 협상을 건너뛴다.
    // Accept가 text/event-stream 같은 JSON 밖의 값이어도 오류 본문이 비지 않고 JSON으로 나간다.
    private static HttpHeaders jsonHeaders(HttpHeaders headers) {
        var copy = new HttpHeaders();
        if (headers != null) {
            copy.putAll(headers);
        }
        copy.setContentType(MediaType.APPLICATION_JSON);
        return copy;
    }

    private FailureResponse fallback(HttpStatusCode status) {
        var serverError = status.is5xxServerError();
        var code = serverError ? "INTERNAL_SERVER_ERROR" : "REQUEST_FAILED";
        var message = serverError ? "서버 오류가 발생했습니다" : "요청을 처리할 수 없습니다";
        return FailureResponse.of(status.value(), code, message, MDC.get("traceId"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpectedException(Exception exception) {
        // 커넥션 풀에서 대기 시한 안에 커넥션을 못 받은 경우다. 트랜잭션 시작이면 CannotCreateTransactionException,
        // JDBC 직접 호출이면 CannotGetJdbcConnectionException으로 감싸 오므로 원인 사슬에서 찾는다(ISE-293).
        // 계약은 "DB 연결 실패 등 일시적 장애"를 503으로 정했고, 클라이언트는 503이면 같은 멱등키로 다시 보낸다.
        if (hasCause(exception, SQLTransientConnectionException.class)) {
            log.warn("DB 커넥션을 받지 못해 503으로 응답한다: {}", exception.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(FailureResponse.of(CommonErrorCode.SERVICE_UNAVAILABLE, null, MDC.get("traceId")));
        }
        log.error("처리하지 못한 요청 예외", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(FailureResponse.of(CommonErrorCode.INTERNAL_SERVER_ERROR, null, MDC.get("traceId")));
    }

    private static boolean hasCause(Throwable exception, Class<? extends Throwable> type) {
        for (var current = exception; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }
}
