package com.iseonjwa.payment.pg.application;

import com.iseonjwa.payment.payment.domain.PgProvider;

import java.util.Objects;

/**
 * 결제 서비스가 PG 모드를 알아야 하는 곳(승인 API의 PG 결정, checkout API)에 주는 읽기 전용 정보다.
 * 설정 객체와 PG 키를 서비스 코드에 노출하지 않으려고 따로 둔다.
 *
 * @param provider 서버 기본 PG. 승인 요청이 PG를 고르지 않으면 이 PG로 처리한다.
 * @param clientKey 토스 결제창이 쓰는 클라이언트 키. Mock 모드이면 null이다.
 * @param mockBypassEnabled 토스 모드에서 승인 API의 Mock 건너뛰기를 받을지(시연용)
 */
public record PgCheckoutProfile(PgProvider provider, String clientKey, boolean mockBypassEnabled) {
    public PgCheckoutProfile {
        Objects.requireNonNull(provider);
    }

    @Override
    public String toString() {
        return "PgCheckoutProfile[provider=" + provider + ", mockBypassEnabled=" + mockBypassEnabled + "]";
    }
}
