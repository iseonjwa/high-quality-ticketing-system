package com.iseonjwa.mockpg.fault;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * {@code prod}와 {@code loadtest}가 함께 켜지면 기동을 막는다. 장애 주입 API는 {@code loadtest}에서 열리므로,
 * 함께 켜면 운영 프로필에서 장애 주입 API가 열리게 된다(mock-pg.md 5절).
 */
@Component
class ProfileGuard {
    ProfileGuard(Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("prod & loadtest"))) {
            throw new IllegalStateException(
                    "prod와 loadtest를 함께 켤 수 없다. loadtest에서 열리는 장애 주입 API가 운영 프로필에서 열리는 것을 막는다");
        }
    }
}
