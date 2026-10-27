package com.highquality.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.highquality.common.exception.ErrorCode;

import java.util.List;

/**
 * 실패 응답 {@code {status, code, message, errors, traceId}} (SA 11-2, #19).
 * errors는 검증 실패일 때만 내보낸다.
 */
public record ErrorResponse(
        int status,
        String code,
        String message,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<FieldErrorDetail> errors,
        String traceId) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return of(errorCode, errorCode.message());
    }

    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(errorCode.status(), errorCode.code(), message, List.of(), TraceIds.current());
    }

    public static ErrorResponse of(ErrorCode errorCode, List<FieldErrorDetail> errors) {
        return new ErrorResponse(errorCode.status(), errorCode.code(), errorCode.message(), errors, TraceIds.current());
    }

    public record FieldErrorDetail(String field, String reason) {
    }
}
