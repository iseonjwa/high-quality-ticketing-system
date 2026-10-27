package iseonjwa.user.global;

import iseonjwa.common.persistence.*;
import iseonjwa.common.web.CommonWebConfiguration;
import iseonjwa.user.application.AuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.context.request.*;
import java.util.Optional;

@Configuration(proxyBeanMethods = false)
@Import({CommonJpaConfiguration.class, CommonWebConfiguration.class})
public class UserConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean CurrentActorProvider currentActorProvider() {
        return () -> {
            var attributes = RequestContextHolder.getRequestAttributes();
            if (attributes instanceof ServletRequestAttributes servlet)
                return Optional.ofNullable((Long) servlet.getRequest().getAttribute("currentUserId"));
            return Optional.empty();
        };
    }

    @Bean ApplicationRunner adminSeed(AuthService auth, @Value("${seed.admin-email}") String email,
                                      @Value("${seed.admin-password}") String password) {
        return args -> auth.seedAdmin(email, password);
    }
}
