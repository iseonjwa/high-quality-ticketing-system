package iseonjwa.common.web;

import iseonjwa.common.identity.IdentityHeaders;
import iseonjwa.common.identity.Roles;
import iseonjwa.common.response.FailureResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

// Gateway가 검증해 전달한 사용자 헤더를 요청 속성으로 옮긴다.
// 인증이 필요한지는 Gateway의 AccessPolicy가 정한다. 여기서는 경로로 판단하지 않고,
// 헤더가 있으면 형식만 검증한다. 사용자 정보가 필요한 컨트롤러는 속성이 없으면 401이 된다.
public class GatewayIdentityFilter extends OncePerRequestFilter {
    public static final String USER_ID_ATTRIBUTE = "currentUserId";
    public static final String USER_ROLE_ATTRIBUTE = "currentUserRole";

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        var userId = request.getHeader(IdentityHeaders.USER_ID);
        var role = request.getHeader(IdentityHeaders.USER_ROLE);
        if (userId == null && role == null) {
            chain.doFilter(request, response);
            return;
        }

        var parsedUserId = parseUserId(userId);
        if (parsedUserId == null || !Roles.isValid(role)) {
            writeUnauthorized(response);
            return;
        }
        request.setAttribute(USER_ID_ATTRIBUTE, parsedUserId);
        request.setAttribute(USER_ROLE_ATTRIBUTE, role);
        chain.doFilter(request, response);
    }

    private Long parseUserId(String value) {
        try {
            var userId = Long.parseLong(value);
            return userId > 0 ? userId : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        var body = FailureResponse.of(401, "UNAUTHORIZED", "인증이 필요합니다", MDC.get("traceId"));
        response.setStatus(401);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(mapper.writeValueAsString(body));
    }
}
