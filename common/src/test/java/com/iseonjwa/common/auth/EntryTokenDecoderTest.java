package com.iseonjwa.common.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// 입장 토큰 검증기가 계약 ①(docs/design/queue/contracts.md 1절)의 형식만 통과시키는지 확인한다
class EntryTokenDecoderTest {

    private static final String SECRET = "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100";
    private static final String OTHER_SECRET = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

    private final EntryTokenDecoder decoder = new EntryTokenDecoder(SECRET);

    @Test
    void 계약대로_만든_토큰은_통과하고_claim을_돌려준다() {
        Jwt jwt = decoder.decode(token(SECRET, Map.of()));

        assertEquals("42", jwt.getSubject());
        assertEquals(31L, ((Number) jwt.getClaim("scheduleId")).longValue());
    }

    @Test
    void 만료된_토큰은_거절한다() {
        Instant issuedAt = Instant.now().minusSeconds(11 * 60);
        assertRejected(token(SECRET, Map.of("iat", issuedAt, "exp", issuedAt.plusSeconds(600))));
    }

    @Test
    void 다른_키로_서명한_토큰은_거절한다() {
        assertRejected(token(OTHER_SECRET, Map.of()));
    }

    @Test
    void typ이_entry가_아니면_거절한다() {
        assertRejected(token(SECRET, Map.of("typ", "access")));
    }

    @Test
    void 결제_토큰은_같은_키로_서명돼도_거절한다() {
        // SA E14: 결제 토큰을 입장 토큰 자리에 넣는 경우. 키가 같아도 typ에서 막혀야 한다
        assertRejected(token(SECRET, Map.of("typ", "payment", "aud", List.of("payment-service"))));
    }

    @Test
    void aud가_gateway가_아니면_거절한다() {
        assertRejected(token(SECRET, Map.of("aud", List.of("payment-service"))));
    }

    @Test
    void sub가_숫자가_아니면_거절한다() {
        assertRejected(token(SECRET, Map.of("sub", "abc")));
    }

    @Test
    void sub가_0이하면_거절한다() {
        assertRejected(token(SECRET, Map.of("sub", "0")));
    }

    @Test
    void scheduleId가_문자열이면_거절한다() {
        assertRejected(token(SECRET, Map.of("scheduleId", "31")));
    }

    @Test
    void jti가_없으면_거절한다() {
        assertRejected(token(SECRET, Map.of(), "jti"));
    }

    @Test
    void 길이가_4096자를_넘으면_거절한다() {
        assertRejected("a".repeat(4097));
    }

    // --- 도우미 ---

    // 계약대로 된 claim에서 overrides만 바꾸고, removes는 빼서 토큰을 만든다
    private String token(String secret, Map<String, Object> overrides, String... removes) {
        Instant issuedAt = Instant.now().minusSeconds(60);
        var claims = new HashMap<String, Object>(Map.of(
                "typ", "entry",
                "aud", List.of("gateway"),
                "sub", "42",
                "scheduleId", 31L,
                "iat", issuedAt,
                "exp", issuedAt.plusSeconds(600),
                "jti", "0123456789abcdef0123456789abcdef"));
        claims.putAll(overrides);
        for (String key : removes) {
            claims.remove(key);
        }

        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(TokenDecoders.signingKey(secret)));
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().claims(c -> c.putAll(claims)).build())).getTokenValue();
    }

    private void assertRejected(String token) {
        assertThrows(JwtException.class, () -> decoder.decode(token));
    }
}