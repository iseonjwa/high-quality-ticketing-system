package com.iseonjwa.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

// SA 11-2: 실패는 { status, code, message, errors, traceId }. errors는 검증 실패일 때만 내보낸다.
@JsonInclude(JsonInclude.Include.ALWAYS)
public record FailureResponse(int status, String code, String message,
                              @JsonInclude(JsonInclude.Include.NON_NULL) List<FieldError> errors,
                              String traceId) {
    public FailureResponse {
        errors = errors == null ? null : List.copyOf(errors);
    }

    public static FailureResponse of(ErrorCode error, List<FieldError> errors, String traceId) {
        return new FailureResponse(error.status(), error.code(), error.message(), errors, traceId);
    }

    public static FailureResponse of(int status, String code, String message, String traceId) {
        return new FailureResponse(status, code, message, null, traceId);
    }
}
