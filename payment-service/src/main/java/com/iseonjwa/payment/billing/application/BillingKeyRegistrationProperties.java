package com.iseonjwa.payment.billing.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 빌링키 등록 횟수 제한 설정. 값은 {@code [가정]}이며 사용자당 PG 발급 시도 수를 묶는다.
 */
@ConfigurationProperties(prefix = "payment.billing-key")
public record BillingKeyRegistrationProperties(RegistrationLimit registrationLimit) {
    private static final int DEFAULT_PER_MINUTE = 3;
    private static final int DEFAULT_PER_DAY = 10;

    public BillingKeyRegistrationProperties {
        registrationLimit = registrationLimit != null ? registrationLimit : new RegistrationLimit(null, null);
    }

    /**
     * @param perMinute 사용자당 최근 60초 안의 PG 발급 시도 상한
     * @param perDay    사용자당 최근 24시간 안의 PG 발급 시도 상한
     */
    public record RegistrationLimit(Integer perMinute, Integer perDay) {
        public RegistrationLimit {
            perMinute = positive(perMinute != null ? perMinute : DEFAULT_PER_MINUTE, "per-minute");
            perDay = positive(perDay != null ? perDay : DEFAULT_PER_DAY, "per-day");
        }

        private static int positive(int value, String name) {
            if (value < 1) {
                throw new IllegalArgumentException("payment.billing-key.registration-limit." + name + "은 1 이상이어야 합니다");
            }
            return value;
        }
    }
}
