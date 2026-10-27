package com.iseonjwa.common.identity;

// Gateway가 검증한 사용자 정보를 서비스에 전달하는 헤더 이름.
public final class IdentityHeaders {
    public static final String USER_ID = "X-User-Id";
    public static final String USER_ROLE = "X-User-Role";

    private IdentityHeaders() {}
}
