package com.iseonjwa.gateway.auth;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Access 토큰(HS256) 서명과 만료를 검증한다. 서비스를 부르지 않고 Gateway 안에서 끝낸다 (SA 7-2).
 * <p>
 * user-service가 발급할 때 지켜야 하는 claim: {@code sub}(숫자 userId), {@code role}(USER·ADMIN),
 * {@code jti}(블랙리스트 키), {@code exp}. 하나라도 없으면 UNAUTHORIZED다.
 */
@Component
public class JwtVerifier {

    private static final Set<String> ROLES = Set.of("USER", "ADMIN");

    private final JwtParser parser;

    public JwtVerifier(AuthProperties properties) {
        if (properties.jwtSecret() == null || properties.jwtSecret().isBlank()) {
            throw new IllegalStateException("gateway.auth.jwt-secret(JWT_SECRET)이 비어 있다");
        }
        this.parser = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(properties.jwtSecret())))
                .build();
    }

    public AuthenticatedUser verify(String token) {
        Claims claims;
        try {
            claims = parser.parseSignedClaims(token).getPayload();
        } catch (ExpiredJwtException e) {
            throw new BusinessException(CommonErrorCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }

        String userId = claims.getSubject();
        String role = claims.get("role", String.class);
        String tokenId = claims.getId();
        if (!isNumber(userId) || !ROLES.contains(role) || tokenId == null || tokenId.isBlank()) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
        return new AuthenticatedUser(userId, role, tokenId);
    }

    private static boolean isNumber(String value) {
        return value != null && !value.isEmpty() && value.chars().allMatch(Character::isDigit);
    }
}
