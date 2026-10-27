package iseonjwa.common.web;

import iseonjwa.common.response.ApiResponse;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;

// 내부 네트워크에서 Gateway가 검증해 전달한 헤더만 신뢰한다.
public class GatewayIdentityFilter extends OncePerRequestFilter {
    private static final PathPattern API_PATH = PathPatternParser.defaultInstance.parse("/api/v1/**");
    private static final PathPattern ADMIN_PATH = PathPatternParser.defaultInstance.parse("/api/v1/admin/**");
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        var path = request.getRequestURI().substring(request.getContextPath().length());
        var requestPath = PathContainer.parsePath(path);
        if (!API_PATH.matches(requestPath) || isPublic(request.getMethod(), path)) {
            chain.doFilter(request, response);
            return;
        }
        try {
            var userId = Long.parseLong(request.getHeader("X-User-Id"));
            var role = request.getHeader("X-User-Role");
            if (userId <= 0 || !List.of("USER", "ADMIN").contains(role)) {
                throw new IllegalArgumentException();
            }
            if (ADMIN_PATH.matches(requestPath) && !"ADMIN".equals(role)) {
                writeError(response, 403, "FORBIDDEN", "접근 권한이 없습니다");
                return;
            }
            request.setAttribute("currentUserId", userId);
            request.setAttribute("currentUserRole", role);
        } catch (IllegalArgumentException | NullPointerException exception) {
            writeError(response, 401, "UNAUTHORIZED", "인증이 필요합니다");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isPublic(String method, String path) {
        return ("POST".equals(method) && List.of("/api/v1/users", "/api/v1/auth/login",
                "/api/v1/auth/refresh").contains(path))
                || ("GET".equals(method) && path.matches("/api/v1/concerts(?:/[^/]+)?"));
    }

    private void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(mapper.writeValueAsString(
                new ApiResponse<>(status, code, message, null, null, MDC.get("traceId"))));
    }
}
