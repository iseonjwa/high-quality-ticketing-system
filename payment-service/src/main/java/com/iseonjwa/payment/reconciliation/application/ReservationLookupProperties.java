package com.iseonjwa.payment.reconciliation.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 예매 서비스 대사 조회 설정(RC-1~RC-6). 기본값은 {@code [가정]}이다.
 *
 * @param enabled        false면 RC-1~RC-6 규칙을 만들지 않는다. 예매 내부 조회 API(ISE-75)가 생기기 전에는 끈다
 * @param baseUrl        기본은 Eureka 서비스 ID이고 Spring Cloud LoadBalancer가 인스턴스를 찾는다
 * @param connectTimeout 연결 시한
 * @param readTimeout    읽기 시한(ADR 0029 결정 2의 2초)
 * @param batchSize      묶음 조회 한 번의 예매 ID 수(API 상한 100)
 * @param pageSize       변경 목록 한 쪽의 크기(API 상한 100)
 */
@ConfigurationProperties(prefix = "payment.reconciliation.reservation")
public record ReservationLookupProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("http://reservation-service") String baseUrl,
        @DefaultValue("1s") Duration connectTimeout,
        @DefaultValue("2s") Duration readTimeout,
        @DefaultValue("100") int batchSize,
        @DefaultValue("100") int pageSize) {
    public static final int MAX_SIZE = 100;

    public ReservationLookupProperties {
        requirePositive(connectTimeout, "connect-timeout");
        requirePositive(readTimeout, "read-timeout");
        requireSize(batchSize, "batch-size");
        requireSize(pageSize, "page-size");
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("payment.reconciliation.reservation.base-url은 비어 있을 수 없습니다");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("payment.reconciliation.reservation." + name + "은 0보다 커야 합니다");
        }
    }

    private static void requireSize(int value, String name) {
        if (value < 1 || value > MAX_SIZE) {
            throw new IllegalArgumentException(
                    "payment.reconciliation.reservation." + name + "은 1~" + MAX_SIZE + "이어야 합니다");
        }
    }
}
