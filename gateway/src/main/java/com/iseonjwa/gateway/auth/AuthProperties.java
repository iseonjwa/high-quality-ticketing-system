package com.iseonjwa.gateway.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Gateway 인증 설정 (config-repo/gateway.yml).
 *
 * @param jwtSecret   Access 토큰 서명 키. Base64로 인코딩한 32바이트 이상 값이며 user-service와 같은 값이다.
 *                    실제 값은 .env의 {@code JWT_SECRET}으로 넣는다 (SA 9-1)
 * @param publicPaths 인증 없이 통과하는 요청. {@code "POST /api/v1/auth/login"}처럼 메서드와 경로 패턴을 적는다
 */
@ConfigurationProperties(prefix = "gateway.auth")
public record AuthProperties(String jwtSecret, List<String> publicPaths) {

    public AuthProperties {
        publicPaths = publicPaths == null ? List.of() : List.copyOf(publicPaths);
    }
}
