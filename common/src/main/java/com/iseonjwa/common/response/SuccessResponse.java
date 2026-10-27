package com.iseonjwa.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;

// SA 11-2: 성공은 { status, message, data, traceId }. 본문이 없는 성공도 data는 null로 내보낸다.
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SuccessResponse<T>(int status, String message, T data, String traceId) {
    public static <T> SuccessResponse<T> of(int status, String message, T data, String traceId) {
        if (status < 200 || status >= 300 || status == 204) {
            throw new IllegalArgumentException("본문을 포함하는 2xx 상태가 필요합니다");
        }
        return new SuccessResponse<>(status, message, data, traceId);
    }
}
