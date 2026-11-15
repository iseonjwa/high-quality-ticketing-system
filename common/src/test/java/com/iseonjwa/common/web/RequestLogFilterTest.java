package com.iseonjwa.common.web;

import com.iseonjwa.common.web.RequestLogPolicy.Mode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(OutputCaptureExtension.class)
class RequestLogFilterTest {
    // 기존 형식 검사는 모든 요청을 남기는 ALL로 하고, 남길지 판정은 PROBLEMS 필터로 따로 본다.
    // PROBLEMS의 느린 기준은 1분으로 둬 테스트 실행 시간이 판정에 섞이지 않게 한다(느린 요청은 따로 짧은 기준으로 본다).
    private final RequestLogFilter filter = new RequestLogFilter(new RequestLogPolicy(Mode.ALL, 500, Duration.ofMillis(200)));
    private final RequestLogFilter problemsFilter =
            new RequestLogFilter(new RequestLogPolicy(Mode.PROBLEMS, 500, Duration.ofMinutes(1)));

    @Test
    void 경로_템플릿이_있으면_템플릿으로_남긴다(CapturedOutput output) throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/payments/42");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/payments/{paymentId}");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> response.setStatus(201));

        assertTrue(output.getOut().contains("요청 GET /api/v1/payments/{paymentId} 201 "), output.getOut());
        assertFalse(output.getOut().contains("/payments/42"));
    }

    @Test
    void 경로_템플릿이_없으면_쿼리_없이_요청_경로를_남긴다(CapturedOutput output) throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/unknown");
        request.setQueryString("token=secret");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> response.setStatus(404));

        assertTrue(output.getOut().contains("요청 POST /api/v1/unknown 404 "), output.getOut());
        assertFalse(output.getOut().contains("secret"));
    }

    @Test
    void actuator_경로는_남기지_않는다(CapturedOutput output) throws Exception {
        var request = new MockHttpServletRequest("GET", "/actuator/health");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

        assertFalse(output.getOut().contains("요청 "), output.getOut());
    }

    @Test
    void 체인이_예외를_던지면_500으로_남기고_다시_던진다(CapturedOutput output) {
        var request = new MockHttpServletRequest("GET", "/api/v1/boom");

        assertThrows(ServletException.class, () -> filter.doFilter(request, new MockHttpServletResponse(), failing()));

        assertTrue(output.getOut().contains("요청 GET /api/v1/boom 500 "), output.getOut());
    }

    @Test
    void ALL은_빠른_정상_응답도_남긴다(CapturedOutput output) throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/concerts"), new MockHttpServletResponse(), respond(200));

        assertTrue(output.getOut().contains("요청 GET /api/v1/concerts 200 "), output.getOut());
    }

    @Test
    void PROBLEMS는_빠른_정상_응답과_업무_거절을_남기지_않는다(CapturedOutput output) throws Exception {
        problemsFilter.doFilter(new MockHttpServletRequest("GET", "/api/v1/concerts"), new MockHttpServletResponse(),
                respond(200));
        problemsFilter.doFilter(new MockHttpServletRequest("POST", "/api/v1/holds"), new MockHttpServletResponse(),
                respond(409));

        assertFalse(output.getOut().contains("요청 "), output.getOut());
    }

    // 경계값: min-status 500 바로 아래(499)는 남기지 않고 500부터 남긴다
    @Test
    void PROBLEMS는_min_status부터_남긴다(CapturedOutput output) throws Exception {
        problemsFilter.doFilter(new MockHttpServletRequest("GET", "/api/v1/below"), new MockHttpServletResponse(),
                respond(499));
        problemsFilter.doFilter(new MockHttpServletRequest("GET", "/api/v1/at"), new MockHttpServletResponse(),
                respond(500));

        assertFalse(output.getOut().contains("/api/v1/below"), output.getOut());
        assertTrue(output.getOut().contains("요청 GET /api/v1/at 500 "), output.getOut());
    }

    @Test
    void PROBLEMS는_503을_남긴다(CapturedOutput output) throws Exception {
        problemsFilter.doFilter(new MockHttpServletRequest("POST", "/api/v1/payments"), new MockHttpServletResponse(),
                respond(503));

        assertTrue(output.getOut().contains("요청 POST /api/v1/payments 503 "), output.getOut());
    }

    @Test
    void PROBLEMS도_체인이_예외를_던지면_500으로_남긴다(CapturedOutput output) {
        var request = new MockHttpServletRequest("GET", "/api/v1/boom");

        assertThrows(ServletException.class,
                () -> problemsFilter.doFilter(request, new MockHttpServletResponse(), failing()));

        assertTrue(output.getOut().contains("요청 GET /api/v1/boom 500 "), output.getOut());
    }

    @Test
    void PROBLEMS는_느린_기준을_넘긴_정상_응답을_남긴다(CapturedOutput output) throws Exception {
        var slowFilter = new RequestLogFilter(new RequestLogPolicy(Mode.PROBLEMS, 500, Duration.ofMillis(20)));
        FilterChain slow = (req, res) -> {
            pause(Duration.ofMillis(40));
            ((MockHttpServletResponse) res).setStatus(200);
        };

        slowFilter.doFilter(new MockHttpServletRequest("GET", "/api/v1/late"), new MockHttpServletResponse(), slow);

        assertTrue(output.getOut().contains("요청 GET /api/v1/late 200 "), output.getOut());
    }

    @Test
    void 설정이_꺼지면_필터를_등록하지_않는다() {
        var runner = new ApplicationContextRunner().withUserConfiguration(CommonWebConfiguration.class);

        runner.run(context -> assertTrue(context.containsBean("requestLogFilter")));
        runner.withPropertyValues("iseonjwa.request-log.enabled=false")
                .run(context -> assertFalse(context.containsBean("requestLogFilter")));
    }

    @Test
    void 등록한_필터는_mode_설정을_따른다(CapturedOutput output) {
        var runner = new ApplicationContextRunner().withUserConfiguration(CommonWebConfiguration.class);

        runner.withPropertyValues("iseonjwa.request-log.mode=problems").run(context -> {
            registeredFilter(context.getBean("requestLogFilter", FilterRegistrationBean.class))
                    .doFilter(new MockHttpServletRequest("GET", "/api/v1/quiet"), new MockHttpServletResponse(),
                            respond(200));
        });
        assertFalse(output.getOut().contains("/api/v1/quiet"), output.getOut());

        // 키가 없으면 지금까지처럼 모든 요청을 남긴다(RequestLogProperties 기본값 ALL)
        runner.run(context -> {
            registeredFilter(context.getBean("requestLogFilter", FilterRegistrationBean.class))
                    .doFilter(new MockHttpServletRequest("GET", "/api/v1/loud"), new MockHttpServletResponse(),
                            respond(200));
        });
        assertTrue(output.getOut().contains("요청 GET /api/v1/loud 200 "), output.getOut());
    }

    @Test
    void 필터_순서는_관측_필터_안쪽이고_거절_필터_바깥이다() {
        new ApplicationContextRunner().withUserConfiguration(CommonWebConfiguration.class).run(context -> {
            var registration = context.getBean("requestLogFilter", FilterRegistrationBean.class);
            assertEquals(Integer.MIN_VALUE + 2, registration.getOrder());
        });
    }

    @Test
    void 운영_공통_설정은_5xx와_200ms_이상만_남긴다() throws IOException {
        assertEquals(new RequestLogProperties(Mode.PROBLEMS, 500, Duration.ofMillis(200)),
                bindFrom("../config-repo/application.yml"));
    }

    @Test
    void 로컬_설정은_모든_요청을_남긴다() throws IOException {
        assertEquals(Mode.ALL, bindFrom("../config-repo/application-local.yml").mode());
    }

    private static RequestLogProperties bindFrom(String path) throws IOException {
        var environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load(path, new FileSystemResource(path))
                .forEach(source -> environment.getPropertySources().addLast(source));
        return Binder.get(environment).bindOrCreate("iseonjwa.request-log", RequestLogProperties.class);
    }

    private static RequestLogFilter registeredFilter(FilterRegistrationBean<?> registration) {
        return (RequestLogFilter) registration.getFilter();
    }

    private static FilterChain respond(int status) {
        return (req, res) -> ((MockHttpServletResponse) res).setStatus(status);
    }

    private static FilterChain failing() {
        return (req, res) -> {
            throw new ServletException("실패");
        };
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
