package com.iseonjwa.mockpg.billing;

/**
 * 빌링키 발급 요청. 형식이 틀리면 만들 때 실패해서 400 {@code INVALID_REQUEST}가 된다({@code InvalidRequestHandler}).
 */
record BillingKeyRequest(String authKey, String customerKey) {
    BillingKeyRequest {
        if (authKey == null || authKey.isBlank()) {
            throw new IllegalArgumentException("authKey가 필요합니다");
        }
        if (customerKey == null || customerKey.isBlank()) {
            throw new IllegalArgumentException("customerKey가 필요합니다");
        }
    }
}
