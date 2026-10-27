package iseonjwa.reservation.global;

import iseonjwa.common.persistence.*;
import iseonjwa.common.web.CommonWebConfiguration;
import iseonjwa.common.messaging.CommonMessagingConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.Optional;

@Configuration(proxyBeanMethods = false)
@Import({CommonJpaConfiguration.class, CommonWebConfiguration.class, CommonMessagingConfiguration.class})
public class ServiceConfiguration {
    @Bean
    CurrentActorProvider currentActorProvider() {
        return () -> {
            var attributes = RequestContextHolder.getRequestAttributes();
            if (attributes instanceof ServletRequestAttributes servlet) {
                return Optional.ofNullable((Long) servlet.getRequest().getAttribute("currentUserId"));
            }
            return Optional.empty();
        };
    }
}
