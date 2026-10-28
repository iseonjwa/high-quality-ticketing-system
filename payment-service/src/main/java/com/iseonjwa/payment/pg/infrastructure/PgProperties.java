package com.iseonjwa.payment.pg.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param provider 사용할 PG 어댑터. 지금은 mock만 구현했고 toss는 M4에서 추가한다.
 */
@ConfigurationProperties(prefix = "payment.pg")
public record PgProperties(Provider provider, Mock mock, Duration connectTimeout, Duration readTimeout) {
    public enum Provider { MOCK, TOSS }

    public PgProperties {
        if (provider == null) {
            throw new IllegalArgumentException("payment.pg.provider를 mock 또는 toss로 설정해야 합니다");
        }
        if (provider == Provider.MOCK && (mock == null || mock.baseUrl() == null || mock.baseUrl().isBlank())) {
            throw new IllegalArgumentException("payment.pg.mock.base-url이 필요합니다");
        }
        // SA 9-4: 연결 0.5초, 응답 2초
        connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofMillis(500);
        readTimeout = readTimeout != null ? readTimeout : Duration.ofSeconds(2);
    }

    public record Mock(String baseUrl) {}
}
