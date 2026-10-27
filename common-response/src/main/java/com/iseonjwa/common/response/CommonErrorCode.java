package com.iseonjwa.common.response;

/**
 * 모든 서비스가 함께 쓰는 에러 코드 (SA 11-3).
 * METHOD_NOT_ALLOWED·INTERNAL_ERROR·GATEWAY_TIMEOUT은 SA 표에 없지만 405·500·504도
 * 실패 형태로 내보내야 해서(SA 11-2) 추가했다.
 */
public enum CommonErrorCode implements ErrorCode {

    VALIDATION_FAILED(400, "요청 값이 올바르지 않습니다"),
    UNAUTHORIZED(401, "인증이 필요합니다"),
    TOKEN_EXPIRED(401, "토큰이 만료되었습니다"),
    TOKEN_REVOKED(401, "로그아웃된 토큰입니다"),
    FORBIDDEN(403, "권한이 없습니다"),
    NOT_FOUND(404, "대상을 찾을 수 없습니다"),
    METHOD_NOT_ALLOWED(405, "지원하지 않는 요청 방식입니다"),
    IDEMPOTENCY_KEY_REUSED(409, "같은 멱등키로 다른 요청을 보냈습니다"),
    RATE_LIMITED(429, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요"),
    INTERNAL_ERROR(500, "서버 오류가 발생했습니다"),
    SERVICE_UNAVAILABLE(503, "일시적으로 서비스를 이용할 수 없습니다"),
    GATEWAY_TIMEOUT(504, "응답 시간이 초과되었습니다");

    private final int status;
    private final String message;

    CommonErrorCode(int status, String message) {
        this.status = status;
        this.message = message;
    }

    /**
     * 프레임워크가 낸 HTTP 상태(경로 없음, 405, 라우팅 실패 등)에 맞는 공통 코드를 고른다.
     * 표에 없는 4xx는 VALIDATION_FAILED, 5xx는 INTERNAL_ERROR로 본다.
     */
    public static CommonErrorCode fromStatus(int status) {
        return switch (status) {
            case 400 -> VALIDATION_FAILED;
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 429 -> RATE_LIMITED;
            case 503 -> SERVICE_UNAVAILABLE;
            case 504 -> GATEWAY_TIMEOUT;
            default -> status >= 400 && status < 500 ? VALIDATION_FAILED : INTERNAL_ERROR;
        };
    }

    @Override
    public int getStatus() {
        return status;
    }

    @Override
    public String getCode() {
        return name();
    }

    @Override
    public String getMessage() {
        return message;
    }
}
