package com.iseonjwa.payment.pg.infrastructure;

import com.iseonjwa.payment.pg.application.PgClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PgProperties.class)
public class PgConfiguration {
    // Note: provider가 mock일 때만 만든다. provider가 비었거나 mock인데 base-url이 없으면 PgProperties 바인딩에서
    // 기동이 실패한다. toss는 M4에서 TossPgAdapter와 함께 추가하며, 그때까지는 PgClient 빈이 없다.
    @Bean
    @ConditionalOnProperty(name = "payment.pg.provider", havingValue = "mock")
    PgClient mockPgClient(PgProperties properties) {
        return MockPgAdapter.create(properties.mock().baseUrl(), properties.connectTimeout(),
                properties.readTimeout());
    }
}
