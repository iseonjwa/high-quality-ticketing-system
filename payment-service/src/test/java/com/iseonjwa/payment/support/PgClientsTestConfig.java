package com.iseonjwa.payment.support;

import com.iseonjwa.payment.payment.domain.PgProvider;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgClients;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * PG 호출을 한 {@link PgClient} 빈으로 모으는 테스트 설정. 서비스는 {@link PgClients}만 주입받으므로 테스트가 PG를 대체하려면
 * 이 설정을 {@code @Import}하고 {@code @MockitoBean PgClient}(대체)나 {@code @MockitoSpyBean PgClient}(실제 Mock PG 어댑터를 감쌈)를 쓴다.
 * PG별로 다르게 보내려면 {@link StubPgClients}를 주입받아 {@code route}한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PgClientsTestConfig {
    // 대체·스파이의 대상이 되는 기본 PG. 설정이 만든 실제 Mock 어댑터다
    @Bean
    PgClient defaultTestPgClient(@Qualifier("pgClients") PgClients real) {
        return real.of(PgProvider.MOCK);
    }

    @Bean
    @Primary
    StubPgClients stubPgClients(PgClient defaultTestPgClient) {
        return new StubPgClients(defaultTestPgClient);
    }
}
