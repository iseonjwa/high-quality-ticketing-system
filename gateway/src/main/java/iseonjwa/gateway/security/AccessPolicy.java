package iseonjwa.gateway.security;

import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.Arrays;

// 요청이 어떤 인증을 요구하는지 판정하는 유일한 곳이다. 서비스는 경로로 판단하지 않는다.
// 패턴은 한 번만 만들고, 요청에서는 Gateway가 이미 파싱한 경로를 그대로 비교한다.
// 라우터와 같은 PathPattern을 쓰므로 인코딩·matrix parameter 변형도 라우터가 보는 경로로 판정한다.
final class AccessPolicy {
    enum Access { PUBLIC, USER, ADMIN }

    private static final PathPattern ADMIN_API = pattern("/api/v1/admin/**");

    private static final PathPattern[] PUBLIC_POST = patterns(
            "/api/v1/users",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/password-change");

    private static final PathPattern[] PUBLIC_GET = patterns(
            "/api/v1/concerts",
            "/api/v1/concerts/*");

    private AccessPolicy() {}

    static Access of(HttpMethod method, PathContainer path) {
        if (ADMIN_API.matches(path)) {
            return Access.ADMIN;
        }
        if (HttpMethod.POST.equals(method) && matchesAny(PUBLIC_POST, path)) {
            return Access.PUBLIC;
        }
        if (HttpMethod.GET.equals(method) && matchesAny(PUBLIC_GET, path)) {
            return Access.PUBLIC;
        }
        return Access.USER;
    }

    private static boolean matchesAny(PathPattern[] patterns, PathContainer path) {
        for (var pattern : patterns) {
            if (pattern.matches(path)) {
                return true;
            }
        }
        return false;
    }

    private static PathPattern pattern(String value) {
        return PathPatternParser.defaultInstance.parse(value);
    }

    private static PathPattern[] patterns(String... values) {
        return Arrays.stream(values).map(AccessPolicy::pattern).toArray(PathPattern[]::new);
    }
}
