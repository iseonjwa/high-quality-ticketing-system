package com.iseonjwa.gateway.auth;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 모든 요청의 인증 (SA 7-2, 11-1).
 * <ol>
 *   <li>클라이언트가 보낸 {@code X-User-Id}·{@code X-User-Role}을 지운다(위조 방지)</li>
 *   <li>인증 없이 통과하는 경로면 그대로 보낸다</li>
 *   <li>Access 토큰의 서명·만료·필수 claim을 검증한다 (없음·위조 401 UNAUTHORIZED, 만료 401 TOKEN_EXPIRED)</li>
 *   <li>로그아웃 블랙리스트를 확인한다 (401 TOKEN_REVOKED)</li>
 *   <li>{@code X-User-Id}·{@code X-User-Role}을 붙여 서비스로 보낸다</li>
 * </ol>
 * 실패는 예외로 끝내고 {@code GatewayErrorHandler}가 실패 응답 형식으로 만든다.
 * 운영자 API 권한(ADMIN)과 본인 확인은 각 서비스가 확인한다 (SA 9-2 요청 차단 계층).
 */
@Component
public class AuthenticationFilter implements GlobalFilter, Ordered {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLE_HEADER = "X-User-Role";

    private static final String BEARER_PREFIX = "Bearer ";

    private final PublicPathMatcher publicPathMatcher;
    private final JwtVerifier jwtVerifier;
    private final TokenBlacklist tokenBlacklist;

    public AuthenticationFilter(PublicPathMatcher publicPathMatcher, JwtVerifier jwtVerifier,
                                TokenBlacklist tokenBlacklist) {
        this.publicPathMatcher = publicPathMatcher;
        this.jwtVerifier = jwtVerifier;
        this.tokenBlacklist = tokenBlacklist;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(USER_ID_HEADER);
                    headers.remove(USER_ROLE_HEADER);
                })
                .build();

        if (publicPathMatcher.isPublic(request.getMethod(), request.getPath().value())) {
            return chain.filter(exchange.mutate().request(request).build());
        }

        AuthenticatedUser user;
        try {
            user = jwtVerifier.verify(bearerToken(request));
        } catch (BusinessException e) {
            return Mono.error(e);
        }

        return tokenBlacklist.isRevoked(user.tokenId())
                .flatMap(revoked -> {
                    if (revoked) {
                        return Mono.error(new BusinessException(CommonErrorCode.TOKEN_REVOKED));
                    }
                    ServerHttpRequest authenticated = request.mutate()
                            .header(USER_ID_HEADER, user.userId())
                            .header(USER_ROLE_HEADER, user.role())
                            .build();
                    return chain.filter(exchange.mutate().request(authenticated).build());
                });
    }

    private String bearerToken(ServerHttpRequest request) {
        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
        return authorization.substring(BEARER_PREFIX.length()).trim();
    }

    /** 라우팅 필터보다 먼저, 다른 전역 필터보다도 먼저 실행한다. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
