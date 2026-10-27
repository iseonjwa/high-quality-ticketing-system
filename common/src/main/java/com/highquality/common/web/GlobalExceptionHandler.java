package com.highquality.common.web;

import com.highquality.common.exception.BusinessException;
import com.highquality.common.exception.CommonErrorCode;
import com.highquality.common.response.ErrorResponse;
import com.highquality.common.response.ErrorResponse.FieldErrorDetail;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * MVC 서비스의 예외를 실패 응답 형식으로 바꾼다(SA 11-2).
 * 서비스는 scanBasePackages에 com.highquality.common을 넣어 이 클래스를 가져온다.
 * Gateway(WebFlux)는 이 패키지를 스캔하지 않으므로 로드되지 않는다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        return respond(ErrorResponse.of(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        List<FieldErrorDetail> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new FieldErrorDetail(fieldError.getField(), fieldError.getDefaultMessage()))
                .toList();
        return respond(ErrorResponse.of(CommonErrorCode.VALIDATION_FAILED, errors));
    }

    // 깨진 JSON, 필수 파라미터 누락, 타입 불일치
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception e) {
        return respond(ErrorResponse.of(CommonErrorCode.VALIDATION_FAILED));
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(Exception e) {
        return respond(ErrorResponse.of(CommonErrorCode.NOT_FOUND));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return respond(ErrorResponse.of(CommonErrorCode.METHOD_NOT_ALLOWED));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("처리되지 않은 예외", e);
        return respond(ErrorResponse.of(CommonErrorCode.INTERNAL_ERROR));
    }

    private ResponseEntity<ErrorResponse> respond(ErrorResponse body) {
        return ResponseEntity.status(body.status()).body(body);
    }
}
