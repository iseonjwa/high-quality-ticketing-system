package com.iseonjwa.common.response;

/**
 * 성공 응답 {@code { status, message, data, traceId }} (SA 11-2).
 * {@code status}는 HTTP 상태 코드와 같고 업무 상태는 {@code data.status}에 둔다.
 * 본문 없는 성공도 200 + {@code data: null}로 보낸다.
 * {@code traceId}는 비워서 만들면 common-web이 응답 직전에 채운다.
 */
public record SuccessResponse<T>(int status, String message, T data, String traceId) {

    private static final String DEFAULT_MESSAGE = "요청이 처리되었습니다";

    public static <T> SuccessResponse<T> ok(T data) {
        return of(200, DEFAULT_MESSAGE, data);
    }

    public static <T> SuccessResponse<T> ok(String message, T data) {
        return of(200, message, data);
    }

    public static <T> SuccessResponse<T> created(String message, T data) {
        return of(201, message, data);
    }

    public static <T> SuccessResponse<T> accepted(String message, T data) {
        return of(202, message, data);
    }

    public static <T> SuccessResponse<T> of(int status, String message, T data) {
        return new SuccessResponse<>(status, message, data, null);
    }

    public SuccessResponse<T> withTraceId(String traceId) {
        return new SuccessResponse<>(status, message, data, traceId);
    }
}
