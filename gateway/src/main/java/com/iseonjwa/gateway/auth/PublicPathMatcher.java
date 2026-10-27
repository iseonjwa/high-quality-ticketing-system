package com.iseonjwa.gateway.auth;

import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;

/**
 * 인증 없이 통과하는 요청인지 판단한다. 설정 값 형식: {@code "POST /api/v1/auth/login"}.
 */
@Component
public class PublicPathMatcher {

    private final List<Rule> rules;

    public PublicPathMatcher(AuthProperties properties) {
        this.rules = properties.publicPaths().stream().map(Rule::parse).toList();
    }

    public boolean isPublic(HttpMethod method, String path) {
        PathContainer pathContainer = PathContainer.parsePath(path);
        return rules.stream().anyMatch(rule -> rule.method().equals(method) && rule.pattern().matches(pathContainer));
    }

    private record Rule(HttpMethod method, PathPattern pattern) {

        static Rule parse(String value) {
            String[] parts = value.trim().split("\\s+");
            if (parts.length != 2) {
                throw new IllegalArgumentException("public-paths는 \"METHOD /path\" 형식이어야 한다: " + value);
            }
            return new Rule(HttpMethod.valueOf(parts[0]), PathPatternParser.defaultInstance.parse(parts[1]));
        }
    }
}
