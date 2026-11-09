package com.iseonjwa.payment.pg.infrastructure;

import com.iseonjwa.payment.observability.PaymentMetrics;
import com.iseonjwa.payment.pg.application.PgClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PgConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PgConfiguration.class)
            .withBean(PaymentMetrics.class, () -> new PaymentMetrics(new SimpleMeterRegistry()));

    @Test
    void provider가_mock이면_MockPgAdapter를_지표_데코레이터로_감싸_PgClient로_등록한다() {
        runner.withPropertyValues("payment.pg.provider=mock", "payment.pg.mock.base-url=http://127.0.0.1:8090")
                .run(context -> assertInstanceOf(MeteredPgClient.class, context.getBean(PgClient.class)));
    }

    @Test
    void provider가_없으면_기동이_실패한다() {
        runner.run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void mock인데_base_url이_없으면_기동이_실패한다() {
        runner.withPropertyValues("payment.pg.provider=mock")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void provider가_toss이고_테스트_키가_있으면_TossPgAdapter를_지표_데코레이터로_감싸_등록한다() {
        runner.withPropertyValues("payment.pg.provider=toss", "payment.pg.toss.secret-key=test_sk_dummy",
                        "payment.pg.toss.client-key=test_ck_dummy")
                .run(context -> {
                    assertInstanceOf(MeteredPgClient.class, context.getBean(PgClient.class));
                    assertEquals(1, context.getBeansOfType(PgClient.class).size());
                });
    }

    @Test
    void toss의_base_url을_비우면_토스_운영_도메인을_쓴다() {
        runner.withPropertyValues("payment.pg.provider=toss", "payment.pg.toss.secret-key=test_sk_dummy",
                        "payment.pg.toss.client-key=test_ck_dummy")
                .run(context -> assertEquals("https://api.tosspayments.com",
                        context.getBean(PgProperties.class).toss().baseUrl()));
    }

    @Test
    void toss인데_라이브_시크릿_키면_기동이_실패하고_메시지에_키가_없다() {
        runner.withPropertyValues("payment.pg.provider=toss", "payment.pg.toss.secret-key=live_sk_must_not_leak",
                        "payment.pg.toss.client-key=test_ck_dummy")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertFalse(allMessages(context.getStartupFailure()).contains("live_sk_must_not_leak"));
                });
    }

    @Test
    void toss인데_라이브_클라이언트_키면_기동이_실패한다() {
        runner.withPropertyValues("payment.pg.provider=toss", "payment.pg.toss.secret-key=test_sk_dummy",
                        "payment.pg.toss.client-key=live_ck_must_not_leak")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertFalse(allMessages(context.getStartupFailure()).contains("live_ck_must_not_leak"));
                });
    }

    @Test
    void toss인데_키가_비어_있으면_기동이_실패한다() {
        runner.withPropertyValues("payment.pg.provider=toss", "payment.pg.toss.secret-key=",
                        "payment.pg.toss.client-key=")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void toss인데_키_설정이_없으면_기동이_실패한다() {
        runner.withPropertyValues("payment.pg.provider=toss")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void mock이면_토스_키가_없어도_기동하고_토스_어댑터는_만들지_않는다() {
        runner.withPropertyValues("payment.pg.provider=mock", "payment.pg.mock.base-url=http://127.0.0.1:8090")
                .run(context -> assertEquals(1, context.getBeansOfType(PgClient.class).size()));
    }

    @Test
    void 설정_객체의_toString에는_토스_키가_나오지_않는다() {
        var toss = new PgProperties.Toss("https://api.tosspayments.com", "test_sk_secret_value", "test_ck_client_value");

        assertFalse(toss.toString().contains("test_sk_secret_value"));
        assertFalse(toss.toString().contains("test_ck_client_value"));
        runner.withPropertyValues("payment.pg.provider=toss", "payment.pg.toss.secret-key=test_sk_secret_value",
                        "payment.pg.toss.client-key=test_ck_client_value")
                .run(context -> {
                    var text = context.getBean(PgProperties.class).toString();
                    assertFalse(text.contains("test_sk_secret_value"));
                    assertFalse(text.contains("test_ck_client_value"));
                });
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

    private static String allMessages(Throwable failure) {
        var messages = new StringBuilder();
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append('\n');
        }
        return messages.toString();
    }
}
