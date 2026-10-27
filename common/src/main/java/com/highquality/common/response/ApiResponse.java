package com.highquality.common.response;

/**
 * 성공 응답 {@code {status, message, data, traceId}} (SA 11-2, #19).
 * status는 HTTP 상태 코드다. 업무 상태는 data 안의 status로 표현한다.
 * 본문 없는 성공(로그아웃 등)도 200 + data: null로 내보낸다.
 */
public record ApiResponse<T>(int status, String message, T data, String traceId) {

    public static <T> ApiResponse<T> of(int status, String message, T data) {
        return new ApiResponse<>(status, message, data, TraceIds.current());
    }

    public static <T> ApiResponse<T> ok(T data) {
        return of(200, "요청이 처리되었습니다", data);
    }

    public static <T> ApiResponse<T> created(T data) {
        return of(201, "생성되었습니다", data);
    }
}
