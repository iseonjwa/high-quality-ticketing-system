package iseonjwa.common.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;

@Configuration(proxyBeanMethods = false)
@Import({CommonExceptionHandler.class, CommonRoutingExceptionHandler.class})
public class CommonWebConfiguration {
    @Bean
    GatewayIdentityFilter gatewayIdentityFilter() {
        return new GatewayIdentityFilter();
    }
}
