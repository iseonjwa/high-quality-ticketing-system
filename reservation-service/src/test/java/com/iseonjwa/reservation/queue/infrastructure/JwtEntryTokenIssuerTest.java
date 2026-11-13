package com.iseonjwa.reservation.queue.infrastructure;

import com.iseonjwa.common.auth.EntryTokenDecoder;
import com.iseonjwa.reservation.queue.domain.EntryToken;
import com.iseonjwa.reservation.queue.domain.EntryTokenJti;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 입장 토큰 발급기가 계약 ①대로 만들고, 그 토큰을 Gateway의 검증기(EntryTokenDecoder)가 받아들이는지 확인한다
class JwtEntryTokenIssuerTest {

    private static final String SECRET = "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100";
    private static final long USER_ID = 42L;
    private static final long SCHEDULE_ID = 31L;
    // 1분 전 발급(아직 유효). 끝자리 789ms를 붙여 초 버림을 확인한다
    private static final long ISSUED_AT_MILLIS = (System.currentTimeMillis() - 60_000) / 1000 * 1000 + 789;

    private final JwtEntryTokenIssuer issuer = new JwtEntryTokenIssuer(SECRET);
    private final EntryTokenDecoder decoder = new EntryTokenDecoder(SECRET);

    @Test
    void 발급한_토큰을_검증기가_받아들이고_계약의_claim이_나온다() {
        Jwt jwt = decoder.decode(issuer.issue(USER_ID, SCHEDULE_ID, ISSUED_AT_MILLIS).value());

        assertEquals("HS256", jwt.getHeaders().get("alg"));
        assertEquals("entry", jwt.getClaimAsString("typ"));
        assertEquals(List.of("gateway"), jwt.getAudience());
        assertEquals("42", jwt.getSubject());
        assertEquals(31L, ((Number) jwt.getClaim("scheduleId")).longValue());
        assertTrue(jwt.getId().matches("[0-9a-f]{32}"), jwt.getId());
    }

    @Test
    void 발급_시각을_초로_버리고_만료는_10분_뒤다() {
        EntryToken token = issuer.issue(USER_ID, SCHEDULE_ID, ISSUED_AT_MILLIS);
        Jwt jwt = decoder.decode(token.value());

        Instant issuedAt = Instant.ofEpochSecond(ISSUED_AT_MILLIS / 1000);
        assertEquals(issuedAt, jwt.getIssuedAt());
        assertEquals(issuedAt.plusSeconds(600), jwt.getExpiresAt());
        assertEquals(jwt.getExpiresAt(), token.expiresAt()); // 응답의 만료와 토큰 안의 exp가 같다
    }

    @Test
    void 같은_발급_시각이면_토큰_문자열이_같다() {
        String first = issuer.issue(USER_ID, SCHEDULE_ID, ISSUED_AT_MILLIS).value();
        String second = issuer.issue(USER_ID, SCHEDULE_ID, ISSUED_AT_MILLIS).value();

        assertEquals(first, second); // 재발급 때 같은 토큰을 줄 수 있다(ADR 0031 결정 9)
    }

    @Test
    void 발급_시각이_1ms만_달라도_jti가_다르다() {
        String a = decoder.decode(issuer.issue(USER_ID, SCHEDULE_ID, ISSUED_AT_MILLIS).value()).getId();
        String b = decoder.decode(issuer.issue(USER_ID, SCHEDULE_ID, ISSUED_AT_MILLIS + 1).value()).getId();

        assertNotEquals(a, b); // iat은 같은 초여도 jti는 ms로 구분된다
    }

    @Test
    void 토큰의_jti는_대기열_정리가_다시_계산하는_값과_같다() {
        String jti = decoder.decode(issuer.issue(USER_ID, SCHEDULE_ID, ISSUED_AT_MILLIS).value()).getId();

        assertEquals(EntryTokenJti.of(SCHEDULE_ID, USER_ID, ISSUED_AT_MILLIS), jti);
    }
}