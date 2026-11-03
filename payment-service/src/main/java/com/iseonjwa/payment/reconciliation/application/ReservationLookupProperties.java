package com.iseonjwa.payment.reconciliation.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 예매 서비스 대사 조회 설정(RC-1~RC-6). 기본값은 모두 {@code [가정]}이다.
 *
 * @param enabled        false면 RC-1~RC-6 규칙을 만들지 않는다. 예매 내부 조회 API(ISE-75)가 생기기 전에는 끈다
 * @param baseUrl        기본은 Eureka 서비스 ID이고 {@code load-balanced}가 true면 Spring Cloud LoadBalancer가 인스턴스를 찾는다
 * @param connectTimeout 연결 시한
 * @param readTimeout    읽기 시한(ADR 0029 결정 2의 2초)
 * @param batchSize      묶음 조회 한 번의 예매 ID 수(API 상한 100)
 * @param loadBalanced   true면 {@code base-url}의 호스트를 Eureka 서비스 ID로 보고 LoadBalancer로 인스턴스를 찾는다.
 *                       false면 인터셉터를 붙이지 않고 {@code base-url}로 바로 부른다(직접 주소, 로컬 실행)
 * @param missingBatchMinSize 묶음 하나에서 이 수 이상 물었는데 하나도 오지 않으면 조회 실패로 본다
 * @param missingMinCount     회차 전체에서 빠진 예매가 이 수 이상이고 {@code missingMaxRatio}를 넘으면 조회 실패로 본다
 * @param missingMaxRatio     물은 예매 대비 빠진 예매 비율의 상한. 결제는 예매가 발급한 토큰으로만 생겨 정상이면 예매 없음이 거의 없다
 * @param maxChangePages      변경 목록을 읽는 최대 쪽 수. 넘으면 조회 실패로 본다
 */
@ConfigurationProperties(prefix = "payment.reconciliation.reservation")
public record ReservationLookupProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("http://reservation-service") String baseUrl,
        @DefaultValue("1s") Duration connectTimeout,
        @DefaultValue("2s") Duration readTimeout,
        @DefaultValue("100") int batchSize,
        @DefaultValue("true") boolean loadBalanced,
        @DefaultValue("2") int missingBatchMinSize,
        @DefaultValue("5") int missingMinCount,
        @DefaultValue("0.1") double missingMaxRatio,
        @DefaultValue("1000") int maxChangePages) {
    public static final int MAX_SIZE = 100;

    public ReservationLookupProperties {
        requirePositive(connectTimeout, "connect-timeout");
        requirePositive(readTimeout, "read-timeout");
        requireSize(batchSize, "batch-size");
        if (missingBatchMinSize < 1 || missingMinCount < 1 || maxChangePages < 1) {
            throw new IllegalArgumentException(
                    "payment.reconciliation.reservation.missing-batch-min-size, missing-min-count, max-change-pages는 1 이상이어야 합니다");
        }
        if (missingMaxRatio < 0 || missingMaxRatio > 1) {
            throw new IllegalArgumentException("payment.reconciliation.reservation.missing-max-ratio는 0~1이어야 합니다");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("payment.reconciliation.reservation.base-url은 비어 있을 수 없습니다");
        }
    }

    public ReservationViewLoader.Limits limits() {
        return new ReservationViewLoader.Limits(batchSize, missingBatchMinSize, missingMinCount, missingMaxRatio,
                maxChangePages);
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
