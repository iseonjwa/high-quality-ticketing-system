package com.highquality.gatewayedge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.context.reactive.ReactiveWebApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class GatewayEdgeApplicationTests {

    @Autowired
    ApplicationContext context;

    @Test
    void contextLoads() {
    }

    // common을 의존해도 MVC가 딸려 오지 않아 리액티브(WebFlux)로 떠야 한다.
    @Test
    void common을_의존해도_리액티브로_뜬다() {
        assertThat(context).isInstanceOf(ReactiveWebApplicationContext.class);
        assertThat(ClassUtils.isPresent("org.springframework.web.servlet.DispatcherServlet", null)).isFalse();
    }

    // common의 MVC 예외 처리기는 Gateway가 스캔하지 않으므로 빈으로 등록되지 않아야 한다.
    @Test
    void common의_MVC_예외_처리기는_로드되지_않는다() {
        assertThat(context.containsBean("globalExceptionHandler")).isFalse();
    }
}
