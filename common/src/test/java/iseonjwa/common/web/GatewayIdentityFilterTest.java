package iseonjwa.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GatewayIdentityFilterTest {
    private final AtomicReference<HttpServletRequest> passed = new AtomicReference<>();

    @Test
    void 사용자_헤더가_없으면_속성_없이_통과한다() throws Exception {
        var response = filter(new MockHttpServletRequest("GET", "/api/v1/concerts"));

        assertEquals(200, response.getStatus());
        assertNull(passed.get().getAttribute("currentUserId"));
    }

    @Test
    void 올바른_사용자_헤더를_요청_속성으로_옮긴다() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/users/me");
        request.addHeader("X-User-Id", "7");
        request.addHeader("X-User-Role", "ADMIN");

        var response = filter(request);

        assertEquals(200, response.getStatus());
        assertEquals(7L, passed.get().getAttribute("currentUserId"));
        assertEquals("ADMIN", passed.get().getAttribute("currentUserRole"));
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "abc, USER", "0, USER", "-1, USER", "1, SUPER", "1, null", "null, USER"})
    void 형식이_잘못된_사용자_헤더는_401이다(String userId, String role) throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/users/me");
        if (userId != null) {
            request.addHeader("X-User-Id", userId);
        }
        if (role != null) {
            request.addHeader("X-User-Role", role);
        }

        var response = filter(request);

        assertEquals(401, response.getStatus());
        assertNull(passed.get());
    }

    private MockHttpServletResponse filter(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        new GatewayIdentityFilter().doFilter(request, response, (req, res) -> passed.set((HttpServletRequest) req));
        return response;
    }
}
