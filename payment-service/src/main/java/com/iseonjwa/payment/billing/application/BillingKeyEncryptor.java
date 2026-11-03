package com.iseonjwa.payment.billing.application;

/**
 * 빌링키 원문을 저장용 암호문으로 바꾸는 포트(ADR 0042). 원문은 PG 호출 직전에만 복호화하고 로그에 남기지 않는다.
 */
public interface BillingKeyEncryptor {
    String encrypt(String plain);

    // 암호문이 바뀌었거나 다른 키로 암호화됐으면 IllegalStateException
    String decrypt(String stored);
}
