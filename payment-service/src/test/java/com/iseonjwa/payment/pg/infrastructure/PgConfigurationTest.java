package com.iseonjwa.payment.pg.infrastructure;

import com.iseonjwa.payment.observability.PaymentMetrics;
import com.iseonjwa.payment.payment.domain.PgProvider;
import com.iseonjwa.payment.pg.application.PgClients;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgConfigurationTest {
    private static final String MOCK_URL = "payment.pg.mock.base-url=http://127.0.0.1:8090";
    private static final String TOSS_SECRET = "payment.pg.toss.secret-key=test_sk_dummy";
    private static final String TOSS_CLIENT = "payment.pg.toss.client-key=test_ck_dummy";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PgConfiguration.class)
            .withBean(PaymentMetrics.class, () -> new PaymentMetrics(new SimpleMeterRegistry()));

    @Test
    void provider가_mock이면_Mock_어댑터를_지표_데코레이터로_감싸_PgClients에_등록한다() {
        runner.withPropertyValues("payment.pg.provider=mock", MOCK_URL)
                .run(context -> assertInstanceOf(MeteredPgClient.class,
                        context.getBean(PgClients.class).of(PgProvider.MOCK)));
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
    void toss_모드여도_mock_base_url이_없으면_기동이_실패한다() {
        runner.withPropertyValues("payment.pg.provider=toss", TOSS_SECRET, TOSS_CLIENT)
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void provider가_toss이고_테스트_키가_있으면_두_어댑터를_모두_지표_데코레이터로_감싸_등록한다() {
        runner.withPropertyValues("payment.pg.provider=toss", MOCK_URL, TOSS_SECRET, TOSS_CLIENT)
                .run(context -> {
                    var clients = context.getBean(PgClients.class);
                    assertInstanceOf(MeteredPgClient.class, clients.of(PgProvider.TOSS));
                    assertInstanceOf(MeteredPgClient.class, clients.of(PgProvider.MOCK));
                });
    }

    @Test
    void mock_모드여도_토스_키가_있으면_토스_어댑터를_만든다() {
        runner.withPropertyValues("payment.pg.provider=mock", MOCK_URL, TOSS_SECRET, TOSS_CLIENT)
                .run(context -> assertInstanceOf(MeteredPgClient.class,
                        context.getBean(PgClients.class).of(PgProvider.TOSS)));
    }

    @Test
    void 토스_키가_없으면_토스_어댑터를_만들지_않고_TOSS를_달라고_하면_CLIENT_ERROR로_답한다() {
        runner.withPropertyValues("payment.pg.provider=mock", MOCK_URL)
                .run(context -> {
                    var clients = context.getBean(PgClients.class);
                    assertEquals(new com.iseonjwa.payment.pg.application.PgClient.Unknown(
                            com.iseonjwa.payment.pg.application.UnknownCause.CLIENT_ERROR),
                            clients.of(PgProvider.TOSS).findPayment(new com.iseonjwa.payment.pg.application.PgOrderId(java.util.UUID.randomUUID())));
                    assertNotNull(clients.billing());
                });
    }

    @Test
    void 빌링은_토스_모드에서도_Mock_클라이언트다() {
        runner.withPropertyValues("payment.pg.provider=toss", MOCK_URL, TOSS_SECRET, TOSS_CLIENT)
                .run(context -> {
                    var clients = context.getBean(PgClients.class);
                    assertEquals(clients.of(PgProvider.MOCK), clients.billing());
                });
    }

    @Test
    void toss의_base_url을_비우면_토스_운영_도메인을_쓴다() {
        runner.withPropertyValues("payment.pg.provider=toss", MOCK_URL, TOSS_SECRET, TOSS_CLIENT)
                .run(context -> assertEquals("https://api.tosspayments.com",
                        context.getBean(PgProperties.class).toss().baseUrl()));
    }

    @Test
    void toss인데_라이브_시크릿_키면_기동이_실패하고_메시지에_키가_없다() {
        runner.withPropertyValues("payment.pg.provider=toss", MOCK_URL,
                        "payment.pg.toss.secret-key=live_sk_must_not_leak", TOSS_CLIENT)
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertFalse(allMessages(context.getStartupFailure()).contains("live_sk_must_not_leak"));
                });
    }

    @Test
    void mock_모드에서_토스_라이브_시크릿_키면_기동이_실패하고_메시지에_키가_없다() {
        runner.withPropertyValues("payment.pg.provider=mock", MOCK_URL,
                        "payment.pg.toss.secret-key=live_sk_must_not_leak", TOSS_CLIENT)
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertFalse(allMessages(context.getStartupFailure()).contains("live_sk_must_not_leak"));
                });
    }

    @Test
    void toss인데_라이브_클라이언트_키면_기동이_실패한다() {
        runner.withPropertyValues("payment.pg.provider=toss", MOCK_URL, TOSS_SECRET,
                        "payment.pg.toss.client-key=live_ck_must_not_leak")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertFalse(allMessages(context.getStartupFailure()).contains("live_ck_must_not_leak"));
                });
    }

    @Test
    void mock_모드에서_시크릿_키만_있으면_기동이_실패한다() {
        runner.withPropertyValues("payment.pg.provider=mock", MOCK_URL, TOSS_SECRET)
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void mock_모드에서_클라이언트_키만_있으면_기동이_실패한다() {
        runner.withPropertyValues("payment.pg.provider=mock", MOCK_URL, TOSS_CLIENT)
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void toss인데_키가_비어_있으면_기동이_실패한다() {
        runner.withPropertyValues("payment.pg.provider=toss", MOCK_URL, "payment.pg.toss.secret-key=",
                        "payment.pg.toss.client-key=")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void toss인데_키_설정이_없으면_기동이_실패한다() {
        runner.withPropertyValues("payment.pg.provider=toss", MOCK_URL)
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void mock이면_토스_키가_비어_있어도_기동한다() {
        runner.withPropertyValues("payment.pg.provider=mock", MOCK_URL, "payment.pg.toss.secret-key=",
                        "payment.pg.toss.client-key=")
                .run(context -> assertNotNull(context.getBean(PgClients.class)));
    }

    @Test
    void provider는_대소문자를_가리지_않고_mock과_toss로_바인딩된다() {
        runner.withPropertyValues("payment.pg.provider=mock", MOCK_URL)
                .run(context -> assertEquals(PgProvider.MOCK, context.getBean(PgProperties.class).provider()));
        runner.withPropertyValues("payment.pg.provider=TOSS", MOCK_URL, TOSS_SECRET, TOSS_CLIENT)
                .run(context -> assertEquals(PgProvider.TOSS, context.getBean(PgProperties.class).provider()));
    }

    @Test
    void 건너뛰기_허용은_기본이_true이고_설정으로_끌_수_있다() {
        runner.withPropertyValues("payment.pg.provider=mock", MOCK_URL)
                .run(context -> assertTrue(context.getBean(PgProperties.class).mockBypassEnabled()));
        runner.withPropertyValues("payment.pg.provider=toss", MOCK_URL, TOSS_SECRET, TOSS_CLIENT,
                        "payment.pg.mock-bypass-enabled=false")
                .run(context -> {
                    assertFalse(context.getBean(PgProperties.class).mockBypassEnabled());
                    assertFalse(context.getBean(com.iseonjwa.payment.pg.application.PgCheckoutProfile.class)
                            .mockBypassEnabled());
                });
    }

    @Test
    void 설정_객체의_toString에는_토스_키가_나오지_않는다() {
        var toss = new PgProperties.Toss("https://api.tosspayments.com", "test_sk_secret_value", "test_ck_client_value");

        assertFalse(toss.toString().contains("test_sk_secret_value"));
        assertFalse(toss.toString().contains("test_ck_client_value"));
        runner.withPropertyValues("payment.pg.provider=toss", MOCK_URL, "payment.pg.toss.secret-key=test_sk_secret_value",
                        "payment.pg.toss.client-key=test_ck_client_value")
                .run(context -> {
                    var text = context.getBean(PgProperties.class).toString();
                    assertFalse(text.contains("test_sk_secret_value"));
                    assertFalse(text.contains("test_ck_client_value"));
                });
    }

    @Test
    void 연결_시간과_응답_시간의_기본값은_SA_9_4를_따른다() {
        runner.withPropertyValues("payment.pg.provider=mock", MOCK_URL)
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
