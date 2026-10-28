package com.iseonjwa.payment.pg.infrastructure;

import com.iseonjwa.payment.pg.application.PgClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PgProperties.class)
public class PgConfiguration {
    // Note: payment.pg.provider를 mock으로 고른 때만 만든다. toss는 M4에서 TossPgAdapter와 함께 추가하며,
    // 그때까지 toss를 고르면 PgClient 빈이 없어 기동이 실패한다(잘못된 설정을 조용히 넘기지 않는다).
    @Bean
    @ConditionalOnProperty(name = "payment.pg.provider", havingValue = "mock")
    PgClient mockPgClient(PgProperties properties) {
        return MockPgAdapter.create(properties.mock().baseUrl(), properties.connectTimeout(),
                properties.readTimeout());
    }
}
