package com.iseonjwa.gateway.support;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/** 테스트용 Access 토큰. user-service가 발급할 토큰과 같은 claim을 쓴다. */
public final class TestTokens {

    /** Base64("iseonjwa-test-secret-key-0123456789abcdef") — 32바이트 이상 */
    public static final String SECRET = "aXNlb25qd2EtdGVzdC1zZWNyZXQta2V5LTAxMjM0NTY3ODlhYmNkZWY=";
    public static final String OTHER_SECRET = "b3RoZXItc2VjcmV0LWtleS1mb3ItdGVzdGluZy0wMTIzNDU2Nzg5";

    private TestTokens() {
    }

    public static String valid(String userId, String role, String jti) {
        return builder(SECRET, Duration.ofMinutes(30)).subject(userId).claim("role", role).id(jti).compact();
    }

    public static String expired(String userId) {
        return builder(SECRET, Duration.ofMinutes(-1)).subject(userId).claim("role", "USER").id("jti-expired").compact();
    }

    public static String signedWithOtherKey(String userId) {
        return builder(OTHER_SECRET, Duration.ofMinutes(30)).subject(userId).claim("role", "USER").id("jti-other").compact();
    }

    public static String withoutJti(String userId) {
        return builder(SECRET, Duration.ofMinutes(30)).subject(userId).claim("role", "USER").compact();
    }

    private static JwtBuilder builder(String secret, Duration validFor) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuedAt(Date.from(now.minus(Duration.ofMinutes(5))))
                .expiration(Date.from(now.plus(validFor)))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret)));
    }
}
