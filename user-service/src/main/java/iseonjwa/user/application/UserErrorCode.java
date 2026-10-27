package iseonjwa.user.application;

import iseonjwa.common.response.ErrorCode;

public enum UserErrorCode implements ErrorCode {
    EMAIL_DUPLICATED(409, "이미 가입한 이메일입니다"),
    INVALID_CREDENTIALS(401, "이메일 또는 비밀번호가 올바르지 않습니다"),
    ACCOUNT_LOCKED(423, "로그인 시도가 너무 많아 계정이 잠겼습니다. 운영자에게 비밀번호 초기화를 요청하세요"),
    PASSWORD_CHANGE_REQUIRED(403, "임시 비밀번호입니다. 비밀번호를 변경한 뒤 로그인하세요"),
    TOKEN_EXPIRED(401, "토큰이 만료됐습니다"),
    TOKEN_REVOKED(401, "토큰을 사용할 수 없습니다");

    private final int status;
    private final String message;

    UserErrorCode(int status, String message) {
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
