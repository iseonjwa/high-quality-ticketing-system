package com.iseonjwa.gateway.overload;

import com.iseonjwa.gateway.security.GatewayErrorWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class InFlightLimitFilterTest {
    private static final int LIMIT = 2;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final InFlightLimitFilter filter = filter(LIMIT);
    private final AtomicInteger forwarded = new AtomicInteger();
    // 응답이 끝나지 않은 요청을 흉내 낸다. 신호를 보내기 전까지 자리를 잡고 있다
    private final List<Sinks.Empty<Void>> pending = new ArrayList<>();
    private final GatewayFilterChain holdingChain = exchange -> {
        forwarded.incrementAndGet();
        var sink = Sinks.<Void>empty();
        pending.add(sink);
        return sink.asMono();
    };

    @Test
    void 상한_안의_요청은_전달하고_응답이_끝나면_자리를_반납한다() {
        filter.filter(exchange("/api/v1/queue/status"), exchange -> Mono.empty()).block();

        assertThat(filter.inFlight()).isZero();
        assertThat(meters.get(InFlightLimitFilter.IN_FLIGHT_METER).gauge().value()).isZero();
        assertThat(meters.get(InFlightLimitFilter.LIMIT_METER).gauge().value()).isEqualTo(LIMIT);
    }

    @Test
    void 처리_중인_요청이_상한에_닿으면_다음_요청은_전달하지_않고_503_GATEWAY_OVERLOADED다() {
        hold(LIMIT);
        var rejected = exchange("/api/v1/queue/status");

        filter.filter(rejected, holdingChain).block();

        assertThat(forwarded).hasValue(LIMIT);
        assertThat(rejected.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(rejected.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
        assertThat(responseCode(rejected)).isEqualTo("GATEWAY_OVERLOADED");
        assertThat(meters.get(InFlightLimitFilter.REJECTED_METER).counter().count()).isEqualTo(1);
        // 거절은 자리를 잡지 않는다
        assertThat(filter.inFlight()).isEqualTo(LIMIT);
    }

    @Test
    void 자리가_반납되면_다음_요청을_다시_받는다() {
        hold(LIMIT);
        pending.getFirst().tryEmitEmpty();

        var next = exchange("/api/v1/queue/status");
        var subscription = filter.filter(next, holdingChain).subscribe();

        assertThat(forwarded).hasValue(LIMIT + 1);
        assertThat(next.getResponse().getStatusCode()).isNull();
        subscription.dispose();
    }

    @Test
    void 뒤쪽이_오류로_끝나도_자리를_반납한다() {
        assertThatThrownBy(() -> filter.filter(exchange("/api/v1/queue/status"),
                exchange -> Mono.error(new IllegalStateException("뒤쪽 실패"))).block())
                .isInstanceOf(IllegalStateException.class);

        assertThat(filter.inFlight()).isZero();
    }

    @Test
    void 뒤쪽이_Mono를_만들기_전에_예외를_던져도_자리를_반납한다() {
        assertThatThrownBy(() -> filter.filter(exchange("/api/v1/queue/status"), exchange -> {
            throw new IllegalStateException("조립 중 실패");
        }).block()).isInstanceOf(IllegalStateException.class);

        assertThat(filter.inFlight()).isZero();
    }

    @Test
    void 클라이언트가_연결을_끊어_취소되면_자리를_반납한다() {
        var subscriptions = hold(LIMIT);
        assertThat(filter.inFlight()).isEqualTo(LIMIT);

        subscriptions.forEach(Disposable::dispose);

        assertThat(filter.inFlight()).isZero();
    }

    @Test
    void 구독되지_않은_요청은_자리를_잡지_않는다() {
        filter.filter(exchange("/api/v1/queue/status"), holdingChain);

        assertThat(filter.inFlight()).isZero();
        assertThat(forwarded).hasValue(0);
    }

    @Test
    void 운영자_경로는_상한이_차도_세지_않고_전달한다() {
        hold(LIMIT);
        var admin = exchange("/api/v1/admin/demo/pg/faults");

        filter.filter(admin, exchange -> {
            forwarded.incrementAndGet();
            return Mono.empty();
        }).block();

        assertThat(forwarded).hasValue(LIMIT + 1);
        assertThat(admin.getResponse().getStatusCode()).isNull();
        assertThat(filter.inFlight()).isEqualTo(LIMIT);
    }

    @Test
    void 인증_필터보다_먼저_돈다() {
        // GatewayAuthenticationFilter(-100), EntryTokenFilter(-90)
        assertThat(filter.getOrder()).isLessThan(-100);
    }

    @Test
    void 상한이_1보다_작거나_다시_시도_간격이_1초보다_짧으면_뜨지_않는다() {
        assertThatThrownBy(() -> filter(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InFlightLimitFilter(writer(), new SimpleMeterRegistry(), 1, Duration.ofMillis(500)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 운영_설정의_상한과_다시_시도_간격을_읽고_액추에이터는_요청_포트와_다른_관리_포트에_있다() throws IOException {
        var environment = new StandardEnvironment();
        var loader = new YamlPropertySourceLoader();
        loader.load("gateway", new FileSystemResource("../config-repo/gateway.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        loader.load("application", new FileSystemResource("../config-repo/application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));

        // 앱과 같은 변환 규칙("2s" → Duration)으로 읽으려고 Binder를 쓴다
        var binder = new Binder(ConfigurationPropertySources.get(environment));
        assertThat(binder.bind("iseonjwa.gateway.in-flight.max-requests", Integer.class).get()).isEqualTo(64);
        assertThat(binder.bind("iseonjwa.gateway.in-flight.retry-after", Duration.class).get())
                .isEqualTo(Duration.ofSeconds(2));
        // 액추에이터(헬스·Prometheus 수집)는 관리 포트의 별도 서버라 Gateway 필터 체인을 거치지 않는다
        assertThat(environment.getProperty("management.server.port")).isEqualTo("9080");
        assertThat(environment.getProperty("server.port")).isEqualTo("8080");
    }

    private List<Disposable> hold(int count) {
        var subscriptions = new ArrayList<Disposable>();
        for (int i = 0; i < count; i++) {
            subscriptions.add(filter.filter(exchange("/api/v1/queue/status"), holdingChain).subscribe());
        }
        return subscriptions;
    }

    private InFlightLimitFilter filter(int limit) {
        return new InFlightLimitFilter(writer(), meters, limit, Duration.ofSeconds(2));
    }

    private static GatewayErrorWriter writer() {
        return new GatewayErrorWriter(JsonMapper.builder().build(), mock(Tracer.class));
    }

    private static MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
    }

    private static String responseCode(MockServerWebExchange exchange) {
        var body = exchange.getResponse().getBodyAsString().block();
        return JsonMapper.builder().build().readTree(body).get("code").asString();
    }
}
