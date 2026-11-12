package com.iseonjwa.gateway.observability;

import com.iseonjwa.common.web.RequestLogPolicy.Mode;
import com.iseonjwa.common.web.RequestLogProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(OutputCaptureExtension.class)
class RequestLogWebFilterTest {
    // 기존 형식 검사는 모든 요청을 남기는 ALL로 하고, 남길지 판정은 PROBLEMS 필터로 따로 본다
    private final RequestLogWebFilter filter = new RequestLogWebFilter(new RequestLogProperties(Mode.ALL, null, null));
    private final RequestLogWebFilter problemsFilter =
            new RequestLogWebFilter(new RequestLogProperties(Mode.PROBLEMS, 500, Duration.ofMinutes(1)));

    @Test
    void 정상_응답은_상태와_경로를_한_줄로_남긴다(CapturedOutput output) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/concerts?token=secret"));
        WebFilterChain chain = ex -> {
            ex.getResponse().setStatusCode(HttpStatus.OK);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertTrue(output.getOut().contains("요청 GET /api/v1/concerts 200 "), output.getOut());
        assertFalse(output.getOut().contains("secret"));
    }

    @Test
    void 체인이_401을_쓰면_401로_남긴다(CapturedOutput output) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/users/me"));
        WebFilterChain chain = ex -> {
            ex.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertTrue(output.getOut().contains("요청 GET /api/v1/users/me 401 "), output.getOut());
    }

    @Test
    void 체인이_상태가_있는_예외를_던지면_그_상태로_남기고_다시_던진다(CapturedOutput output) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/nowhere"));
        WebFilterChain chain = ex -> Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));

        assertThrows(ResponseStatusException.class, () -> filter.filter(exchange, chain).block());

        assertTrue(output.getOut().contains("요청 GET /nowhere 404 "), output.getOut());
    }

    @Test
    void 체인이_일반_예외를_던지면_500으로_남기고_다시_던진다(CapturedOutput output) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/boom"));
        WebFilterChain chain = ex -> Mono.error(new IllegalStateException("실패"));

        assertThrows(IllegalStateException.class, () -> filter.filter(exchange, chain).block());

        assertTrue(output.getOut().contains("요청 GET /api/v1/boom 500 "), output.getOut());
    }

    @Test
    void 클라이언트가_끊으면_CANCELLED로_남긴다(CapturedOutput output) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/slow"));
        WebFilterChain chain = ex -> Mono.never();

        filter.filter(exchange, chain).subscribe().dispose();

        assertTrue(output.getOut().contains("요청 GET /api/v1/slow CANCELLED "), output.getOut());
    }

    @Test
    void actuator_경로는_남기지_않는다(CapturedOutput output) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health"));

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertFalse(output.getOut().contains("요청 "), output.getOut());
    }

    @Test
    void 설정이_꺼지면_필터를_등록하지_않는다() {
        var runner = new ApplicationContextRunner().withUserConfiguration(RequestLogWebFilter.class);

        runner.run(context -> assertTrue(context.containsBean("requestLogWebFilter")));
        runner.withPropertyValues("iseonjwa.request-log.enabled=false")
                .run(context -> assertFalse(context.containsBean("requestLogWebFilter")));
    }

    @Test
    void 설정의_mode를_읽어_PROBLEMS면_빠른_정상_응답을_남기지_않는다(CapturedOutput output) {
        new ApplicationContextRunner().withUserConfiguration(RequestLogWebFilter.class)
                .withPropertyValues("iseonjwa.request-log.mode=problems")
                .run(context -> context.getBean(RequestLogWebFilter.class)
                        .filter(MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/ok")), ok()).block());

        assertFalse(output.getOut().contains("요청 GET /api/v1/ok"), output.getOut());
    }

    @Test
    void PROBLEMS는_빠른_정상_응답과_4xx를_남기지_않는다(CapturedOutput output) {
        problemsFilter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/ok")), ok()).block();
        problemsFilter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/seats")),
                respond(HttpStatus.CONFLICT)).block();

        assertFalse(output.getOut().contains("요청 "), output.getOut());
    }

    @Test
    void PROBLEMS는_5xx_응답을_남긴다(CapturedOutput output) {
        problemsFilter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/queue")),
                respond(HttpStatus.SERVICE_UNAVAILABLE)).block();

        assertTrue(output.getOut().contains("요청 GET /api/v1/queue 503 "), output.getOut());
    }

    @Test
    void PROBLEMS는_상태가_있는_예외의_5xx도_남긴다(CapturedOutput output) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/route"));
        WebFilterChain chain = ex -> Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY));

        assertThrows(ResponseStatusException.class, () -> problemsFilter.filter(exchange, chain).block());

        assertTrue(output.getOut().contains("요청 GET /api/v1/route 502 "), output.getOut());
    }

    @Test
    void PROBLEMS는_클라이언트가_끊은_요청을_남긴다(CapturedOutput output) {
        problemsFilter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/slow")), ex -> Mono.never())
                .subscribe().dispose();

        assertTrue(output.getOut().contains("요청 GET /api/v1/slow CANCELLED "), output.getOut());
    }

    @Test
    void PROBLEMS는_느린_기준을_넘긴_정상_응답을_남긴다(CapturedOutput output) {
        var slowFilter = new RequestLogWebFilter(new RequestLogProperties(Mode.PROBLEMS, 500, Duration.ofMillis(20)));
        // Note: Mono.delay는 다른 스레드에서 끝나 block()이 doFinally보다 먼저 돌아올 수 있다. 같은 스레드에서 기다린다
        WebFilterChain chain = ex -> Mono.defer(() -> {
            pause(Duration.ofMillis(40));
            return ok().filter(ex);
        });

        slowFilter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/late")), chain).block();

        assertTrue(output.getOut().contains("요청 GET /api/v1/late 200 "), output.getOut());
    }

    @Test
    void 운영_설정은_5xx와_200ms_이상만_남긴다() throws IOException {
        var environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("gateway", new FileSystemResource("../config-repo/gateway.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));

        var properties = Binder.get(environment).bind("iseonjwa.request-log", RequestLogProperties.class).get();

        assertEquals(new RequestLogProperties(Mode.PROBLEMS, 500, Duration.ofMillis(200)), properties);
    }

    @Test
    void 관측_필터_order_0_바로_안쪽에서_돈다() {
        assertEquals(1, filter.getOrder());
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static WebFilterChain ok() {
        return respond(HttpStatus.OK);
    }

    private static WebFilterChain respond(HttpStatus status) {
        return ex -> {
            ex.getResponse().setStatusCode(status);
            return Mono.empty();
        };
    }
}
