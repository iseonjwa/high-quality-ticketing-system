package com.iseonjwa.payment.pg.infrastructure;

import com.iseonjwa.payment.observability.PaymentMetrics;
import com.iseonjwa.payment.pg.application.PgCheckoutProfile;
import com.iseonjwa.payment.pg.application.PgClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PgProperties.class)
public class PgConfiguration {
    // Note: provider에 맞는 어댑터 하나만 만든다. provider가 비었거나 mock인데 base-url이 없거나 toss인데 테스트 키가 아니면
    // PgProperties 바인딩에서 기동이 실패한다. 어댑터는 MeteredPgClient가 감싸서 돌려준다. 어댑터를 새로 더할 때도 같은 데코레이터로 감싼다.
    @Bean
    @ConditionalOnProperty(name = "payment.pg.provider", havingValue = "mock")
    PgClient mockPgClient(PgProperties properties, PaymentMetrics metrics) {
        return new MeteredPgClient(MockPgAdapter.create(properties.mock().baseUrl(), properties.connectTimeout(),
                properties.readTimeout()), metrics);
    }

    @Bean
    @ConditionalOnProperty(name = "payment.pg.provider", havingValue = "toss")
    PgClient tossPgClient(PgProperties properties, PaymentMetrics metrics) {
        var toss = properties.toss();
        return new MeteredPgClient(TossPgAdapter.create(toss.baseUrl(), toss.secretKey(), properties.connectTimeout(),
                properties.readTimeout()), metrics);
    }

    @Bean
    PgCheckoutProfile pgCheckoutProfile(PgProperties properties) {
        return switch (properties.provider()) {
            case MOCK -> new PgCheckoutProfile(PgCheckoutProfile.Provider.MOCK, null);
            case TOSS -> new PgCheckoutProfile(PgCheckoutProfile.Provider.TOSS, properties.toss().clientKey());
        };
    }
}
