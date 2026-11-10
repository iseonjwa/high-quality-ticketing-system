package com.iseonjwa.payment.pg.infrastructure;

import com.iseonjwa.payment.observability.PaymentMetrics;
import com.iseonjwa.payment.pg.application.PgCheckoutProfile;
import com.iseonjwa.payment.pg.application.PgClients;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PgProperties.class)
public class PgConfiguration {
    // Note: Mock 어댑터는 늘 만든다(빌링과 건너뛰기가 쓴다). 토스 어댑터는 시크릿 키가 있을 때만 만든다. provider가 mock이어도
    // 키가 있으면 만들어, 모드를 mock으로 되돌린 뒤에도 진행 중인 토스 결제를 조회할 수 있게 한다. 키 형식과 mock base-url은
    // PgProperties 바인딩에서 검사해 어긋나면 기동이 실패한다. 어댑터는 MeteredPgClient가 감싸서 돌려준다. 어댑터를 새로 더할 때도 같은 데코레이터로 감싼다.
    @Bean
    PgClients pgClients(PgProperties properties, PaymentMetrics metrics) {
        var mock = new MeteredPgClient(MockPgAdapter.create(properties.mock().baseUrl(), properties.connectTimeout(),
                properties.readTimeout()), metrics);
        MeteredPgClient toss = null;
        if (properties.tossConfigured()) {
            var config = properties.toss();
            toss = new MeteredPgClient(TossPgAdapter.create(config.baseUrl(), config.secretKey(),
                    properties.connectTimeout(), properties.readTimeout()), metrics);
        }
        return new ConfiguredPgClients(mock, toss);
    }

    @Bean
    PgCheckoutProfile pgCheckoutProfile(PgProperties properties) {
        var clientKey = switch (properties.provider()) {
            case MOCK -> null;
            case TOSS -> properties.toss().clientKey();
        };
        return new PgCheckoutProfile(properties.provider(), clientKey, properties.mockBypassEnabled());
    }
}
