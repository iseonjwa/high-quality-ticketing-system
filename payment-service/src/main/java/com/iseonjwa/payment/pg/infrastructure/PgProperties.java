package com.iseonjwa.payment.pg.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param provider 사용할 PG 어댑터. mock이 기본이고 toss는 시연 때만 켠다(ADR 0023).
 */
@ConfigurationProperties(prefix = "payment.pg")
public record PgProperties(Provider provider, Mock mock, Toss toss, Duration connectTimeout, Duration readTimeout) {
    public enum Provider { MOCK, TOSS }

    private static final String TOSS_TEST_SECRET_PREFIX = "test_sk_";
    private static final String TOSS_TEST_CLIENT_PREFIX = "test_ck_";
    private static final String TOSS_DEFAULT_BASE_URL = "https://api.tosspayments.com";

    public PgProperties {
        if (provider == null) {
            throw new IllegalArgumentException("payment.pg.provider를 mock 또는 toss로 설정해야 합니다");
        }
        if (provider == Provider.MOCK && (mock == null || mock.baseUrl() == null || mock.baseUrl().isBlank())) {
            throw new IllegalArgumentException("payment.pg.mock.base-url이 필요합니다");
        }
        if (provider == Provider.TOSS) {
            // Note: 예외 메시지에는 키 값을 넣지 않는다. 테스트 키(test_sk_, test_ck_)만 허용해 라이브 키로는 기동하지 못하게 한다
            if (toss == null || toss.secretKey() == null || !toss.secretKey().startsWith(TOSS_TEST_SECRET_PREFIX)) {
                throw new IllegalArgumentException("payment.pg.toss.secret-key는 토스 테스트 시크릿 키("
                        + TOSS_TEST_SECRET_PREFIX + "로 시작)여야 합니다");
            }
            if (toss.clientKey() == null || !toss.clientKey().startsWith(TOSS_TEST_CLIENT_PREFIX)) {
                throw new IllegalArgumentException("payment.pg.toss.client-key는 토스 테스트 클라이언트 키("
                        + TOSS_TEST_CLIENT_PREFIX + "로 시작)여야 합니다");
            }
        }
        // SA 9-4: 연결 0.5초, 응답 2초
        connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofMillis(500);
        readTimeout = readTimeout != null ? readTimeout : Duration.ofSeconds(2);
    }

    public record Mock(String baseUrl) {}

    /**
     * @param secretKey 서버가 토스 API를 부를 때 쓰는 시크릿 키. 로그와 toString에 남기지 않는다.
     * @param clientKey 결제창이 쓰는 클라이언트 키. 브라우저에 내려가는 값이지만 toString에서는 같이 가린다.
     */
    public record Toss(String baseUrl, String secretKey, String clientKey) {
        public Toss {
            baseUrl = baseUrl == null || baseUrl.isBlank() ? TOSS_DEFAULT_BASE_URL : baseUrl;
        }

        @Override
        public String toString() {
            return "Toss[baseUrl=" + baseUrl + ", secretKey=****, clientKey=****]";
        }
    }
}
