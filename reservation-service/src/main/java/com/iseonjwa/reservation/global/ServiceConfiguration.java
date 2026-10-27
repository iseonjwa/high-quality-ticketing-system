package com.iseonjwa.reservation.global;

import com.iseonjwa.common.messaging.CommonMessagingConfiguration;
import com.iseonjwa.common.persistence.CommonJpaConfiguration;
import com.iseonjwa.common.persistence.CurrentActorProvider;
import com.iseonjwa.common.web.CommonWebConfiguration;
import com.iseonjwa.common.web.GatewayIdentityFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
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
                var userId = servlet.getRequest().getAttribute(GatewayIdentityFilter.USER_ID_ATTRIBUTE);
                return Optional.ofNullable((Long) userId);
            }
            return Optional.empty();
        };
    }
}
