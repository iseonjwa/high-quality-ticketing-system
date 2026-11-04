package com.iseonjwa.common.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@Import({CommonExceptionHandler.class, CommonRoutingExceptionHandler.class})
public class CommonWebConfiguration implements WebMvcConfigurer {
    @Bean
    GatewayIdentityFilter gatewayIdentityFilter() {
        return new GatewayIdentityFilter();
    }

    // 트레이싱 관측 필터(HIGHEST_PRECEDENCE + 1) 안쪽에서 돌아 traceId가 MDC에 있을 때 로그를 쓴다.
    // GatewayIdentityFilter 같은 요청을 거절하는 필터보다 바깥이어야 401·403·404도 한 줄이 남는다.
    // iseonjwa.request-log.enabled=false면 등록하지 않는다(부하 측정 때 로그 양 조절).
    @Bean
    @ConditionalOnProperty(name = "iseonjwa.request-log.enabled", havingValue = "true", matchIfMissing = true)
    FilterRegistrationBean<RequestLogFilter> requestLogFilter() {
        var registration = new FilterRegistrationBean<>(new RequestLogFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return registration;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RequireRoleInterceptor());
    }
}
