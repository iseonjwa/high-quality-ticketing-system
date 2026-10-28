package com.iseonjwa.payment.pg.infrastructure;

import com.iseonjwa.payment.pg.application.PgClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PgConfiguration.class);

    @Test
    void provider가_mock이면_MockPgAdapter를_PgClient로_등록한다() {
        runner.withPropertyValues("payment.pg.provider=mock", "payment.pg.mock.base-url=http://127.0.0.1:8090")
                .run(context -> assertInstanceOf(MockPgAdapter.class, context.getBean(PgClient.class)));
    }

    @Test
    void 아직_없는_provider를_고르면_조용히_넘기지_않고_PgClient_빈이_없다() {
        runner.withPropertyValues("payment.pg.provider=toss")
                .run(context -> assertTrue(context.getBeansOfType(PgClient.class).isEmpty()));
    }

    @Test
    void 연결_시간과_응답_시간의_기본값은_SA_9_4를_따른다() {
        runner.withPropertyValues("payment.pg.provider=mock", "payment.pg.mock.base-url=http://127.0.0.1:8090")
                .run(context -> {
                    var properties = context.getBean(PgProperties.class);
                    assertNotNull(properties);
                    assertEquals(500, properties.connectTimeout().toMillis());
                    assertEquals(2000, properties.readTimeout().toMillis());
                });
    }
}
