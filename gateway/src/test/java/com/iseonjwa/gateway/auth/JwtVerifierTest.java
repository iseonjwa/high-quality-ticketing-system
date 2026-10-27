package com.iseonjwa.gateway.auth;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.gateway.support.TestTokens;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtVerifierTest {

    private final JwtVerifier verifier = new JwtVerifier(new AuthProperties(TestTokens.SECRET, List.of()));

    @Test
    void 유효한_토큰이면_userId_role_jti를_꺼낸다() {
        AuthenticatedUser user = verifier.verify(TestTokens.valid("42", "ADMIN", "jti-1"));

        assertThat(user).isEqualTo(new AuthenticatedUser("42", "ADMIN", "jti-1"));
    }

    @Test
    void 만료된_토큰은_TOKEN_EXPIRED다() {
        assertErrorCode(TestTokens.expired("42"), CommonErrorCode.TOKEN_EXPIRED);
    }

    @Test
    void 다른_키로_서명한_토큰은_UNAUTHORIZED다() {
        assertErrorCode(TestTokens.signedWithOtherKey("42"), CommonErrorCode.UNAUTHORIZED);
    }

    @Test
    void 형식이_깨진_토큰은_UNAUTHORIZED다() {
        assertErrorCode("not-a-jwt", CommonErrorCode.UNAUTHORIZED);
    }

    @Test
    void jti가_없으면_로그아웃할_수_없으므로_UNAUTHORIZED다() {
        assertErrorCode(TestTokens.withoutJti("42"), CommonErrorCode.UNAUTHORIZED);
    }

    @Test
    void 정해지지_않은_role이나_숫자가_아닌_sub는_UNAUTHORIZED다() {
        assertErrorCode(TestTokens.valid("42", "MANAGER", "jti-1"), CommonErrorCode.UNAUTHORIZED);
        assertErrorCode(TestTokens.valid("abc", "USER", "jti-1"), CommonErrorCode.UNAUTHORIZED);
    }

    private void assertErrorCode(String token, CommonErrorCode expected) {
        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }
}
