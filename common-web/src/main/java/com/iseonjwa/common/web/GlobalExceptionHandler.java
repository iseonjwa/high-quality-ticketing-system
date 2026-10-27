package com.iseonjwa.common.web;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.common.response.ErrorCode;
import com.iseonjwa.common.response.ErrorResponse;
import com.iseonjwa.common.response.ErrorResponse.FieldViolation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;

/**
 * 모든 예외를 실패 응답 {@code { status, code, message, errors, traceId }}로 바꾼다 (SA 11-2).
 * Spring MVC 기본 예외(404 경로 없음, 405, 깨진 JSON, 검증 실패 등)는 부모 클래스가 잡아
 * {@link #handleExceptionInternal}로 모이므로 여기서 한 번에 형식을 바꾼다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final TraceIdProvider traceIdProvider;

    public GlobalExceptionHandler(TraceIdProvider traceIdProvider) {
        this.traceIdProvider = traceIdProvider;
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        ErrorResponse body = ErrorResponse.of(errorCode, e.getMessage(), traceIdProvider.currentTraceId());
        return ResponseEntity.status(errorCode.getStatus()).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("처리하지 못한 예외", e);
        ErrorResponse body = ErrorResponse.of(CommonErrorCode.INTERNAL_ERROR, traceIdProvider.currentTraceId());
        return ResponseEntity.status(body.status()).body(body);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        String traceId = traceIdProvider.currentTraceId();
        List<FieldViolation> violations = fieldViolations(ex);
        ErrorResponse error = violations.isEmpty()
                ? ErrorResponse.ofStatus(statusCode.value(), traceId)
                : ErrorResponse.ofValidation(violations, traceId);
        if (statusCode.is5xxServerError()) {
            log.error("서버 오류", ex);
        }
        return ResponseEntity.status(statusCode).headers(headers).body(error);
    }

    private List<FieldViolation> fieldViolations(Exception ex) {
        if (ex instanceof org.springframework.validation.BindException bindException) {
            return fromBindingResult(bindException.getBindingResult());
        }
        if (ex instanceof HandlerMethodValidationException validationException) {
            return validationException.getParameterValidationResults().stream()
                    .flatMap(result -> result.getResolvableErrors().stream()
                            .map(error -> new FieldViolation(
                                    result.getMethodParameter().getParameterName(),
                                    error.getDefaultMessage())))
                    .toList();
        }
        return List.of();
    }

    private List<FieldViolation> fromBindingResult(BindingResult bindingResult) {
        return bindingResult.getFieldErrors().stream()
                .map(GlobalExceptionHandler::toViolation)
                .toList();
    }

    private static FieldViolation toViolation(FieldError fieldError) {
        return new FieldViolation(fieldError.getField(), fieldError.getDefaultMessage());
    }
}
