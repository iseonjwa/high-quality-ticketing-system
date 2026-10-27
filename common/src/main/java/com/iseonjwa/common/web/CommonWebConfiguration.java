package com.iseonjwa.common.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@Import({CommonExceptionHandler.class, CommonRoutingExceptionHandler.class})
public class CommonWebConfiguration implements WebMvcConfigurer {
    @Bean
    GatewayIdentityFilter gatewayIdentityFilter() {
        return new GatewayIdentityFilter();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RequireRoleInterceptor());
    }
}
