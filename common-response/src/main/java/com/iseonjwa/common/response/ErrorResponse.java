package com.iseonjwa.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 실패 응답 {@code { status, code, message, errors, traceId }} (SA 11-2).
 * {@code errors}는 검증 실패일 때만 들어가고, 그 밖에는 응답에서 빠진다.
 */
public record ErrorResponse(
        int status,
        String code,
        String message,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<FieldViolation> errors,
        String traceId) {

    public static ErrorResponse of(ErrorCode errorCode, String traceId) {
        return of(errorCode, errorCode.getMessage(), traceId);
    }

    public static ErrorResponse of(ErrorCode errorCode, String message, String traceId) {
        return new ErrorResponse(errorCode.getStatus(), errorCode.getCode(), message, null, traceId);
    }

    /**
     * 프레임워크가 정한 HTTP 상태를 그대로 두고 코드만 공통 코드로 채운다.
     * 415·406처럼 공통 코드와 상태가 다를 때도 HTTP 상태를 바꾸지 않는다 (SA 11-2).
     */
    public static ErrorResponse ofStatus(int status, String traceId) {
        ErrorCode errorCode = CommonErrorCode.fromStatus(status);
        return new ErrorResponse(status, errorCode.getCode(), errorCode.getMessage(), null, traceId);
    }

    public static ErrorResponse ofValidation(List<FieldViolation> errors, String traceId) {
        ErrorCode errorCode = CommonErrorCode.VALIDATION_FAILED;
        return new ErrorResponse(errorCode.getStatus(), errorCode.getCode(), errorCode.getMessage(), errors, traceId);
    }

    /** 검증 실패한 필드 하나. */
    public record FieldViolation(String field, String reason) {
    }
}
