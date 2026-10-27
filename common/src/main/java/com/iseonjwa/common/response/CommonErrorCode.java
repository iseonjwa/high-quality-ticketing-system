package com.iseonjwa.common.response;

public enum CommonErrorCode implements ErrorCode {
    VALIDATION_FAILED(400, "요청 값이 올바르지 않습니다"),
    UNAUTHORIZED(401, "인증이 필요합니다"),
    FORBIDDEN(403, "접근 권한이 없습니다"),
    NOT_FOUND(404, "대상을 찾을 수 없습니다"),
    METHOD_NOT_ALLOWED(405, "지원하지 않는 요청 메서드입니다"),
    IDEMPOTENCY_KEY_REUSED(409, "같은 멱등키로 다른 요청을 보낼 수 없습니다"),
    UNSUPPORTED_MEDIA_TYPE(415, "지원하지 않는 요청 형식입니다"),
    RATE_LIMITED(429, "요청이 너무 많습니다"),
    INTERNAL_SERVER_ERROR(500, "서버 오류가 발생했습니다"),
    SERVICE_UNAVAILABLE(503, "일시적으로 서비스를 사용할 수 없습니다");

    private final int status;
    private final String message;

    CommonErrorCode(int status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public String message() {
        return message;
    }
}
