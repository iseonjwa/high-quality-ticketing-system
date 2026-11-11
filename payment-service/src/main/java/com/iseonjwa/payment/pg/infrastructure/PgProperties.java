package com.iseonjwa.payment.pg.infrastructure;

import com.iseonjwa.payment.payment.domain.PgProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param provider 서버 기본 PG. mock이 기본이고 toss는 시연 때만 켠다(ADR 0023).
 * @param mockBypassEnabled 토스 모드에서 승인 API의 Mock 건너뛰기를 받을지. 비우면 true다(시연용).
 */
@ConfigurationProperties(prefix = "payment.pg")
public record PgProperties(PgProvider provider, Mock mock, Toss toss, Boolean mockBypassEnabled,
        Duration connectTimeout, Duration readTimeout) {
    private static final String TOSS_TEST_SECRET_PREFIX = "test_sk_";
    private static final String TOSS_TEST_CLIENT_PREFIX = "test_ck_";
    private static final String TOSS_DEFAULT_BASE_URL = "https://api.tosspayments.com";

    public PgProperties {
        if (provider == null) {
            throw new IllegalArgumentException("payment.pg.provider를 mock 또는 toss로 설정해야 합니다");
        }
        // 빌링과 Mock 건너뛰기는 서버 모드와 상관없이 Mock을 쓰므로 base-url은 늘 필요하다
        if (mock == null || mock.baseUrl() == null || mock.baseUrl().isBlank()) {
            throw new IllegalArgumentException("payment.pg.mock.base-url이 필요합니다");
        }
        var hasSecret = toss != null && toss.secretKey() != null && !toss.secretKey().isBlank();
        var hasClient = toss != null && toss.clientKey() != null && !toss.clientKey().isBlank();
        // Note: 예외 메시지에는 키 값을 넣지 않는다. 테스트 키(test_sk_, test_ck_)만 허용해 라이브 키로는 기동하지 못하게 한다.
        // mock 모드여도 키가 있으면 같은 검사를 한다. 진행 중인 토스 결제를 조회하려고 어댑터를 만들기 때문이다
        if (provider == PgProvider.TOSS || hasSecret || hasClient) {
            if (!hasSecret || !toss.secretKey().startsWith(TOSS_TEST_SECRET_PREFIX)) {
                throw new IllegalArgumentException("payment.pg.toss.secret-key는 토스 테스트 시크릿 키("
                        + TOSS_TEST_SECRET_PREFIX + "로 시작)여야 합니다");
            }
            if (!hasClient || !toss.clientKey().startsWith(TOSS_TEST_CLIENT_PREFIX)) {
                throw new IllegalArgumentException("payment.pg.toss.client-key는 토스 테스트 클라이언트 키("
                        + TOSS_TEST_CLIENT_PREFIX + "로 시작)여야 합니다");
            }
        }
        mockBypassEnabled = mockBypassEnabled == null || mockBypassEnabled;
        // SA 9-4: 연결 0.5초, 응답 2초
        connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofMillis(500);
        readTimeout = readTimeout != null ? readTimeout : Duration.ofSeconds(2);
    }

    /** 토스 어댑터를 만들 수 있는지(시크릿 키가 설정돼 있는지). 키가 있으면 위 검사를 통과한 테스트 키다 */
    public boolean tossConfigured() {
        return toss != null && toss.secretKey() != null && !toss.secretKey().isBlank();
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
