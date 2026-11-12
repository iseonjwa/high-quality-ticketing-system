package com.iseonjwa.gateway.observability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(OutputCaptureExtension.class)
class RequestLogWebFilterTest {
    private final RequestLogWebFilter filter = new RequestLogWebFilter();

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
    void 관측_필터_order_0_바로_안쪽에서_돈다() {
        assertEquals(1, filter.getOrder());
    }
}
