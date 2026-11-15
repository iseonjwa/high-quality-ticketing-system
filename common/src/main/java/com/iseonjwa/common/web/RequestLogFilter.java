package com.iseonjwa.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

// 요청의 메서드·경로·상태·처리 시간을 한 줄 남긴다(ISE-182). Gateway의 RequestLogWebFilter와 같은 형식이다.
// traceId는 로그 패턴이 MDC에서 붙인다. 본문·헤더·쿼리 문자열은 남기지 않는다.
// 어떤 요청을 남길지는 iseonjwa.request-log.mode(all·problems)가 정한다(RequestLogPolicy, ADR 0052). 운영 기본값은 config-repo/application.yml이다.
public class RequestLogFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);
    private static final String ACTUATOR_PREFIX = "/actuator";

    private final RequestLogPolicy policy;

    public RequestLogFilter(RequestLogPolicy policy) {
        this.policy = policy;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith(ACTUATOR_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        var start = System.nanoTime();
        var status = 500;
        try {
            chain.doFilter(request, response);
            status = response.getStatus();
        } finally {
            var millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            // Note: 서블릿에는 Gateway의 CANCEL 신호가 없다. 클라이언트가 끊으면 응답을 쓰다 예외가 나므로 위의 500으로 남는다.
            if (policy.shouldLog(status, false, millis)) {
                log.info("요청 {} {} {} {}ms", request.getMethod(), path(request), status, millis);
            }
        }
    }

    // 컨트롤러 경로 템플릿(/payments/{paymentId})을 쓰면 ID가 달라도 같은 경로로 묶인다. 매칭된 핸들러가 없으면 요청 경로를 쓴다.
    private static String path(HttpServletRequest request) {
        var pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern != null ? pattern.toString() : request.getRequestURI();
    }
}
