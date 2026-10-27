package iseonjwa.user.application;

import iseonjwa.common.response.ErrorCode;

public enum UserErrorCode implements ErrorCode {
    EMAIL_DUPLICATED(409, "이미 가입한 이메일입니다"),
    INVALID_CREDENTIALS(401, "이메일 또는 비밀번호가 올바르지 않습니다"),
    TOKEN_EXPIRED(401, "토큰이 만료됐습니다"),
    TOKEN_REVOKED(401, "토큰을 사용할 수 없습니다");
    private final int status;
    private final String message;
    UserErrorCode(int status, String message) { this.status = status; this.message = message; }
    public int status() { return status; }
    public String code() { return name(); }
    public String message() { return message; }
}
