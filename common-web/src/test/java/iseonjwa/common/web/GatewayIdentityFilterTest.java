package iseonjwa.common.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.*;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class GatewayIdentityFilterTest {
    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/admin/check", "/api/v1/admin", "/api/v1/admin;x=1/check",
            "/api/v1/%61dmin/check", "/api/%76%31/admin/check"})
    void USER의_변형된_관리자_경로도_거절한다(String path) throws Exception {
        var request = new MockHttpServletRequest("GET", path);
        request.addHeader("X-User-Id", "1");
        request.addHeader("X-User-Role", "USER");
        var response = new MockHttpServletResponse();
        var called = new AtomicBoolean();

        new GatewayIdentityFilter().doFilter(request, response, (req, res) -> called.set(true));

        assertEquals(403, response.getStatus());
        assertFalse(called.get());
    }

    @Test
    void 인코딩한_API_경로도_인증_검사를_건너뛰지_못한다() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/%76%31/users/me");
        var response = new MockHttpServletResponse();
        var called = new AtomicBoolean();
        new GatewayIdentityFilter().doFilter(request, response, (req, res) -> called.set(true));
        assertEquals(401, response.getStatus());
        assertFalse(called.get());
    }

    @Test
    void 컨텍스트_경로가_있어도_관리자_검사를_적용한다() throws Exception {
        var request = new MockHttpServletRequest("GET", "/tickets/api/v1/admin;x=1/check");
        request.setContextPath("/tickets");
        request.addHeader("X-User-Id", "1");
        request.addHeader("X-User-Role", "USER");
        var response = new MockHttpServletResponse();
        new GatewayIdentityFilter().doFilter(request, response, (req, res) -> fail("USER 전달 금지"));
        assertEquals(403, response.getStatus());
    }
}
