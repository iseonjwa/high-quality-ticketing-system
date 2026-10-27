package com.highquality.common.exception;

/**
 * 모든 서비스와 Gateway가 함께 쓰는 에러 코드(SA 11-3). 도메인 에러 코드는 각 서비스 enum에 둔다.
 * METHOD_NOT_ALLOWED·INTERNAL_ERROR는 11-3 표에 없지만, Spring 기본 오류(405)와 처리 안 된 예외도
 * 실패 형태로 내보내야 해서(11-2) 추가했다.
 */
public enum CommonErrorCode implements ErrorCode {

    VALIDATION_FAILED(400, "요청 값이 올바르지 않습니다"),
    UNAUTHORIZED(401, "인증이 필요합니다"),
    TOKEN_EXPIRED(401, "토큰이 만료되었습니다"),
    TOKEN_REVOKED(401, "로그아웃된 토큰입니다"),
    FORBIDDEN(403, "권한이 없습니다"),
    NOT_FOUND(404, "대상을 찾을 수 없습니다"),
    METHOD_NOT_ALLOWED(405, "지원하지 않는 메서드입니다"),
    IDEMPOTENCY_KEY_REUSED(409, "같은 멱등키로 다른 요청이 들어왔습니다"),
    RATE_LIMITED(429, "요청이 너무 많습니다"),
    INTERNAL_ERROR(500, "서버 오류가 발생했습니다"),
    SERVICE_UNAVAILABLE(503, "일시적으로 처리할 수 없습니다");

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
