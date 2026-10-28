package com.iseonjwa.payment.pg.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param provider 사용할 PG 어댑터. 지금은 mock만 있고 toss는 M4에서 추가한다.
 */
@ConfigurationProperties(prefix = "payment.pg")
public record PgProperties(String provider, Mock mock, Duration connectTimeout, Duration readTimeout) {
    public PgProperties {
        // SA 9-4: 연결 0.5초, 응답 2초
        connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofMillis(500);
        readTimeout = readTimeout != null ? readTimeout : Duration.ofSeconds(2);
    }

    public record Mock(String baseUrl) {}
}
