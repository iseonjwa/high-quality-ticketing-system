package com.iseonjwa.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(OutputCaptureExtension.class)
class RequestLogFilterTest {
    private final RequestLogFilter filter = new RequestLogFilter();

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
        FilterChain failing = (req, res) -> {
            throw new ServletException("실패");
        };

        assertThrows(ServletException.class, () -> filter.doFilter(request, new MockHttpServletResponse(), failing));

        assertTrue(output.getOut().contains("요청 GET /api/v1/boom 500 "), output.getOut());
    }

    @Test
    void 설정이_꺼지면_필터를_등록하지_않는다() {
        var runner = new ApplicationContextRunner().withUserConfiguration(CommonWebConfiguration.class);

        runner.run(context -> assertTrue(context.containsBean("requestLogFilter")));
        runner.withPropertyValues("iseonjwa.request-log.enabled=false")
                .run(context -> assertFalse(context.containsBean("requestLogFilter")));
    }

    @Test
    void 필터_순서는_관측_필터_안쪽이고_거절_필터_바깥이다() {
        new ApplicationContextRunner().withUserConfiguration(CommonWebConfiguration.class).run(context -> {
            var registration = context.getBean("requestLogFilter",
                    org.springframework.boot.web.servlet.FilterRegistrationBean.class);
            assertEquals(Integer.MIN_VALUE + 2, registration.getOrder());
        });
    }
}
