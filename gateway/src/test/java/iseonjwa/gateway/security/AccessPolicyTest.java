package iseonjwa.gateway.security;

import iseonjwa.gateway.security.AccessPolicy.Access;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AccessPolicyTest {
    @ParameterizedTest
    @CsvSource({
            "POST, /api/v1/users",
            "POST, /api/v1/auth/login",
            "POST, /api/v1/auth/refresh",
            "POST, /api/v1/auth/password-change",
            "GET, /api/v1/concerts",
            "GET, /api/v1/concerts/10"
    })
    void 공개_경로는_인증하지_않는다(String method, String path) {
        assertEquals(Access.PUBLIC, of(method, path));
    }

    @ParameterizedTest
    @CsvSource({
            "GET, /api/v1/users/me",
            "POST, /api/v1/auth/logout",
            "POST, /api/v1/concerts",
            "GET, /api/v1/concerts/10/seats",
            "GET, /api/v1/users",
            "PUT, /api/v1/auth/login",
            "GET, /api/v1/reservations/1"
    })
    void 공개로_지정하지_않은_요청은_로그인이_필요하다(String method, String path) {
        assertEquals(Access.USER, of(method, path));
    }

    @ParameterizedTest
    @CsvSource({
            "GET, /api/v1/admin/check",
            "GET, /api/v1/admin",
            "POST, /api/v1/admin/users/1/password-reset",
            "GET, /api/v1/admin;x=1/check",
            "GET, /api/v1/%61dmin/check",
            "GET, /api/%76%31/admin/check"
    })
    void 관리자_경로는_변형해도_관리자_전용이다(String method, String path) {
        assertEquals(Access.ADMIN, of(method, path));
    }

    @Test
    void 관리자_경로는_공개_메서드와_겹쳐도_관리자_전용이다() {
        assertEquals(Access.ADMIN, of("POST", "/api/v1/admin/users"));
    }

    private Access of(String method, String path) {
        return AccessPolicy.of(HttpMethod.valueOf(method), PathContainer.parsePath(path));
    }
}
