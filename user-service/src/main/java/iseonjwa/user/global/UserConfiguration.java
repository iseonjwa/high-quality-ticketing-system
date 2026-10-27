package iseonjwa.user.global;

import iseonjwa.common.persistence.CommonJpaConfiguration;
import iseonjwa.common.persistence.CurrentActorProvider;
import iseonjwa.common.web.CommonWebConfiguration;
import iseonjwa.common.web.GatewayIdentityFilter;
import iseonjwa.user.application.AuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

@Configuration(proxyBeanMethods = false)
@Import({CommonJpaConfiguration.class, CommonWebConfiguration.class})
public class UserConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CurrentActorProvider currentActorProvider() {
        return () -> {
            var attributes = RequestContextHolder.getRequestAttributes();
            if (attributes instanceof ServletRequestAttributes servlet) {
                var userId = servlet.getRequest().getAttribute(GatewayIdentityFilter.USER_ID_ATTRIBUTE);
                return Optional.ofNullable((Long) userId);
            }
            return Optional.empty();
        };
    }

    @Bean
    ApplicationRunner adminSeed(AuthService auth, @Value("${seed.admin-email}") String email,
            @Value("${seed.admin-password}") String password) {
        return args -> auth.seedAdmin(email, password);
    }
}
