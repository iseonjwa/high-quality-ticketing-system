package com.iseonjwa.gateway.overload;

import com.iseonjwa.gateway.security.GatewayErrorWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class InFlightLimitHandlerDecoratorTest {
    private static final int LIMIT = 2;
    private static final String PATH = "/api/v1/queue/status";

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final InFlightLimitHandlerDecorator decorator = decorator(LIMIT);
    private final AtomicInteger forwarded = new AtomicInteger();
    // 응답이 끝나지 않은 요청을 흉내 낸다. 신호를 보내기 전까지 자리를 잡고 있다
    private final List<Sinks.Empty<Void>> pending = new ArrayList<>();
    private final HttpHandler holding = (request, response) -> {
        forwarded.incrementAndGet();
        var sink = Sinks.<Void>empty();
        pending.add(sink);
        return sink.asMono();
    };
    private final HttpHandler limited = decorator.decorate(holding);

    @Test
    void 상한_안의_요청은_전달하고_응답이_끝나면_자리를_반납한다() {
        decorator.decorate((request, response) -> Mono.empty()).handle(request(PATH), new MockServerHttpResponse()).block();

        assertThat(decorator.inFlight()).isZero();
        assertThat(meters.get(InFlightLimitHandlerDecorator.IN_FLIGHT_METER).gauge().value()).isZero();
        assertThat(meters.get(InFlightLimitHandlerDecorator.LIMIT_METER).gauge().value()).isEqualTo(LIMIT);
    }

    @Test
    void 처리_중인_요청이_상한에_닿으면_다음_요청은_전달하지_않고_503_GATEWAY_OVERLOADED다() {
        hold(LIMIT);
        var rejected = new MockServerHttpResponse();

        limited.handle(request(PATH), rejected).block();

        assertThat(forwarded).hasValue(LIMIT);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(rejected.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("3");
        var body = body(rejected);
        assertThat(body.get("code").asString()).isEqualTo("GATEWAY_OVERLOADED");
        // 서버 관측이 열리기 전이라 스팬이 없다
        assertThat(body.get("traceId") == null || body.get("traceId").isNull()).isTrue();
        assertThat(meters.get(InFlightLimitHandlerDecorator.REJECTED_METER).counter().count()).isEqualTo(1);
        // 거절은 자리를 잡지 않는다
        assertThat(decorator.inFlight()).isEqualTo(LIMIT);
    }

    @Test
    void 자리가_반납되면_다음_요청을_다시_받는다() {
        hold(LIMIT);
        pending.getFirst().tryEmitEmpty();

        var next = new MockServerHttpResponse();
        var subscription = limited.handle(request(PATH), next).subscribe();

        assertThat(forwarded).hasValue(LIMIT + 1);
        assertThat(next.getStatusCode()).isNull();
        subscription.dispose();
    }

    @Test
    void 뒤쪽이_오류로_끝나도_자리를_반납한다() {
        HttpHandler failing = (request, response) -> Mono.error(new IllegalStateException("뒤쪽 실패"));

        assertThatThrownBy(() -> decorator.decorate(failing).handle(request(PATH), new MockServerHttpResponse()).block())
                .isInstanceOf(IllegalStateException.class);

        assertThat(decorator.inFlight()).isZero();
    }

    @Test
    void 뒤쪽이_Mono를_만들기_전에_예외를_던져도_자리를_반납한다() {
        HttpHandler throwing = (request, response) -> {
            throw new IllegalStateException("조립 중 실패");
        };

        assertThatThrownBy(() -> decorator.decorate(throwing).handle(request(PATH), new MockServerHttpResponse()).block())
                .isInstanceOf(IllegalStateException.class);

        assertThat(decorator.inFlight()).isZero();
    }

    @Test
    void 클라이언트가_연결을_끊어_취소되면_자리를_반납한다() {
        var subscriptions = hold(LIMIT);
        assertThat(decorator.inFlight()).isEqualTo(LIMIT);

        subscriptions.forEach(Disposable::dispose);

        assertThat(decorator.inFlight()).isZero();
    }

    @Test
    void 구독되지_않은_요청은_자리를_잡지_않는다() {
        limited.handle(request(PATH), new MockServerHttpResponse());

        assertThat(decorator.inFlight()).isZero();
        assertThat(forwarded).hasValue(0);
    }

    @Test
    void 운영자_경로는_상한이_차도_세지_않고_전달한다() {
        hold(LIMIT);
        var admin = new MockServerHttpResponse();

        decorator.decorate((request, response) -> {
            forwarded.incrementAndGet();
            return Mono.empty();
        }).handle(request("/api/v1/admin/demo/pg/faults"), admin).block();

        assertThat(forwarded).hasValue(LIMIT + 1);
        assertThat(admin.getStatusCode()).isNull();
        assertThat(decorator.inFlight()).isEqualTo(LIMIT);
    }

    @Test
    void 핸들러_빌더에_끼우면_거절된_요청은_서버_관측과_WebFilter를_거치지_않는다() {
        // 앱의 HttpHandler와 같은 조립이다(HttpHandlerAutoConfiguration이 customizer를 부른 뒤 build한다)
        var observations = new AtomicInteger();
        var observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public void onStart(Observation.Context context) {
                observations.incrementAndGet();
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });
        var filtered = new AtomicInteger();
        WebFilter countingFilter = (exchange, chain) -> {
            filtered.incrementAndGet();
            return chain.filter(exchange);
        };
        var builder = WebHttpHandlerBuilder
                .webHandler(exchange -> Sinks.<Void>empty().asMono())
                .filter(countingFilter)
                .observationRegistry(observationRegistry);
        decorator.customize(builder);
        var app = builder.build();

        for (int i = 0; i < LIMIT; i++) {
            app.handle(request(PATH), new MockServerHttpResponse()).subscribe();
        }
        // 받은 요청은 서버 관측과 WebFilter를 그대로 거친다
        assertThat(observations).hasValue(LIMIT);
        assertThat(filtered).hasValue(LIMIT);

        var rejected = new MockServerHttpResponse();
        // 데코레이터가 빠지면 거절되지 않고 끝나지 않는 핸들러로 가므로 시한으로 실패시킨다
        app.handle(request(PATH), rejected).block(Duration.ofSeconds(1));

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(observations).hasValue(LIMIT);
        assertThat(filtered).hasValue(LIMIT);
    }

    @Test
    void 상한이_1보다_작거나_다시_시도_간격이_1초보다_짧으면_뜨지_않는다() {
        assertThatThrownBy(() -> decorator(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InFlightLimitHandlerDecorator(writer(), new SimpleMeterRegistry(), 1,
                Duration.ofMillis(500))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 운영_설정의_상한과_다시_시도_간격을_읽고_액추에이터는_요청_포트와_다른_관리_포트에_있다() throws IOException {
        var environment = new StandardEnvironment();
        var loader = new YamlPropertySourceLoader();
        loader.load("gateway", new FileSystemResource("../config-repo/gateway.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        loader.load("application", new FileSystemResource("../config-repo/application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));

        // 앱과 같은 변환 규칙("3s" → Duration)으로 읽으려고 Binder를 쓴다
        var binder = new Binder(ConfigurationPropertySources.get(environment));
        assertThat(binder.bind("iseonjwa.gateway.in-flight.max-requests", Integer.class).get()).isEqualTo(64);
        assertThat(binder.bind("iseonjwa.gateway.in-flight.retry-after", Duration.class).get())
                .isEqualTo(Duration.ofSeconds(3));
        // 액추에이터(헬스·Prometheus 수집)는 관리 포트의 별도 HttpHandler라 이 데코레이터를 거치지 않는다
        assertThat(environment.getProperty("management.server.port")).isEqualTo("9080");
        assertThat(environment.getProperty("server.port")).isEqualTo("8080");
    }

    private List<Disposable> hold(int count) {
        var subscriptions = new ArrayList<Disposable>();
        for (int i = 0; i < count; i++) {
            subscriptions.add(limited.handle(request(PATH), new MockServerHttpResponse()).subscribe());
        }
        return subscriptions;
    }

    private InFlightLimitHandlerDecorator decorator(int limit) {
        return new InFlightLimitHandlerDecorator(writer(), meters, limit, Duration.ofSeconds(3));
    }

    private static GatewayErrorWriter writer() {
        return new GatewayErrorWriter(JsonMapper.builder().build(), mock(Tracer.class));
    }

    private static MockServerHttpRequest request(String path) {
        return MockServerHttpRequest.get(path).build();
    }

    private static JsonNode body(MockServerHttpResponse response) {
        return JsonMapper.builder().build().readTree(response.getBodyAsString().block());
    }
}
