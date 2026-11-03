package com.iseonjwa.payment.reconciliation.infrastructure;

import com.iseonjwa.payment.reconciliation.application.ReservationLookup;
import com.iseonjwa.payment.reconciliation.application.ReservationLookupProperties;
import com.iseonjwa.payment.reconciliation.application.ReservationTargets;
import com.iseonjwa.payment.reconciliation.application.ReservationViewLoader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerInterceptor;
import org.springframework.cloud.client.loadbalancer.LoadBalancerRequestFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 예매 서비스와 대조하는 RC-1~RC-6과 그 입력(조회 어댑터, 스냅숏 로더)을 만든다. {@code
 * payment.reconciliation.reservation.enabled}가 {@code true}일 때만 켠다. 기본값은 꺼짐이다. 예매 내부 조회 API(ISE-75)가 없는
 * 채로 켜면 회차마다 규칙이 실패해 대사 정체 알림이 울린다.
 *
 * <p>Note: 예매 서비스 주소는 {@code load-balanced}(기본 true)일 때 이 어댑터의 RestClient에만 로드밸런서 인터셉터로 붙인다. false면 {@code base-url}로 바로 부른다. 앱 전역의 {@code RestClient.Builder}
 * 빈을 새로 등록하거나 {@code @LoadBalanced}로 바꾸지 않으므로 PG 어댑터 같은 다른 HTTP 호출은 그대로다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReservationLookupProperties.class)
@ConditionalOnProperty(prefix = "payment.reconciliation.reservation", name = "enabled", havingValue = "true")
class ReservationReconciliationConfiguration {
    @Bean
    ReservationLookup reservationLookup(ReservationLookupProperties properties,
            ObjectProvider<LoadBalancerClient> loadBalancer) {
        var client = loadBalancer.getIfAvailable();
        var resolver = client == null || !properties.loadBalanced() ? null : new LoadBalancerInterceptor(client, new LoadBalancerRequestFactory(client));
        return ReservationLookupHttpClient.create(properties.baseUrl(), properties.connectTimeout(),
                properties.readTimeout(), resolver);
    }

    @Bean
    ReservationViewLoader reservationViewLoader(JdbcTemplate jdbc, ReservationLookup lookup,
            ReservationLookupProperties properties) {
        ReservationTargets targets = new JdbcReservationTargets(jdbc);
        return new ReservationViewLoader(targets, lookup, properties.limits());
    }

    @Bean
    ApprovedNotConfirmedRule approvedNotConfirmedRule(JdbcTemplate jdbc) {
        return new ApprovedNotConfirmedRule(jdbc);
    }

    @Bean
    RefundObligationMissingRule refundObligationMissingRule(JdbcTemplate jdbc) {
        return new RefundObligationMissingRule(jdbc);
    }

    @Bean
    RefundedButConfirmedRule refundedButConfirmedRule(JdbcTemplate jdbc) {
        return new RefundedButConfirmedRule(jdbc);
    }

    @Bean
    ConfirmedWithoutPaymentRule confirmedWithoutPaymentRule(JdbcTemplate jdbc) {
        return new ConfirmedWithoutPaymentRule(jdbc);
    }

    @Bean
    AmountMismatchRule amountMismatchRule(JdbcTemplate jdbc) {
        return new AmountMismatchRule(jdbc);
    }

    @Bean
    UserMismatchRule userMismatchRule(JdbcTemplate jdbc) {
        return new UserMismatchRule(jdbc);
    }
}
