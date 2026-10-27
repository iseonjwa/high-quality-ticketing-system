package com.iseonjwa.user;

import com.iseonjwa.user.application.AuthService;
import com.iseonjwa.user.domain.AuthSessions;
import com.iseonjwa.user.domain.AuthTokens;
import com.iseonjwa.user.domain.UserRepository;
import com.iseonjwa.user.infrastructure.security.JwtTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class AuthConfigurationTest {
    private final ApplicationContextRunner tokens = new ApplicationContextRunner()
            .withUserConfiguration(JwtTokens.class)
            .withPropertyValues(
                    "auth.access-secret=test-access-secret-at-least-32-bytes-long",
                    "auth.refresh-secret=test-refresh-secret-at-least-32-bytes-long",
                    "auth.issuer=iseonjwa-auth",
                    "auth.access-seconds=1800",
                    "auth.refresh-seconds=1209600");

    private final ApplicationContextRunner authentication = new ApplicationContextRunner()
            .withUserConfiguration(AuthService.class)
            .withBean(UserRepository.class, () -> mock(UserRepository.class))
            .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class))
            .withBean(AuthTokens.class, () -> mock(AuthTokens.class))
            .withBean(AuthSessions.class, () -> mock(AuthSessions.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withPropertyValues("auth.max-login-failures=5");

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void Access_만료_시간이_양수가_아니면_기동하지_않는다(long seconds) {
        tokens.withPropertyValues("auth.access-seconds=" + seconds)
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void Refresh_만료_시간이_양수가_아니면_기동하지_않는다(long seconds) {
        tokens.withPropertyValues("auth.refresh-seconds=" + seconds)
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void 로그인_실패_제한이_양수가_아니면_기동하지_않는다(int attempts) {
        authentication.withPropertyValues("auth.max-login-failures=" + attempts)
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void 정상_만료_설정으로는_기동하고_토큰을_발급한다() {
        tokens.run(context -> {
            assertNull(context.getStartupFailure());
            assertNotNull(context.getBean(JwtTokens.class).issue(1L, "USER"));
        });
    }

    @Test
    void 정상_로그인_제한으로는_기동한다() {
        authentication.run(context -> {
            assertNull(context.getStartupFailure());
            assertNotNull(context.getBean(AuthService.class));
        });
    }
}
