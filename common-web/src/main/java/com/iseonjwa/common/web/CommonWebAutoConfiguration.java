package com.iseonjwa.common.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * 서비스가 common-web을 의존성으로 추가하기만 하면 응답·에러 형식이 적용되게 한다 (M1 완료 기준).
 * Boot 기본 에러 컨트롤러보다 먼저 등록해야 기본 컨트롤러가 빠진다.
 */
@AutoConfiguration(beforeName = "org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Import({GlobalExceptionHandler.class, TraceIdResponseAdvice.class, CommonErrorController.class})
public class CommonWebAutoConfiguration {

    @Bean
    public TraceIdProvider traceIdProvider() {
        return new TraceIdProvider();
    }
}
