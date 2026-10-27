package com.iseonjwa.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.test.context.ActiveProfiles;

/**
 * config-repo/gateway.yml의 라우트가 읽히고 경로가 맞는 서비스로 가는지 확인한다.
 * Config 서버 없이 파일을 직접 읽는다.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.config.import=optional:file:../config-repo/gateway.yml",
        "eureka.client.enabled=false",
        "management.tracing.export.zipkin.enabled=false"
})
class GatewayRoutesTest {

    @Autowired
    RouteLocator routeLocator;

    @Test
    void 서비스마다_lb_라우트가_있다() {
        Map<String, String> routes = routeLocator.getRoutes()
                .collectMap(Route::getId, route -> route.getUri().toString())
                .block();

        assertThat(routes).containsEntry("user-service", "lb://user-service")
                .containsEntry("reservation-service", "lb://reservation-service")
                .containsEntry("payment-service", "lb://payment-service")
                .doesNotContainKey("mock-pg");
    }

    @Test
    void 경로가_담당_서비스로_간다() {
        Map<String, String> predicates = routeLocator.getRoutes()
                .collect(Collectors.toMap(Route::getId, route -> route.getPredicate().toString()))
                .block();

        assertThat(predicates.get("user-service")).contains("/api/v1/auth/**", "/api/v1/users/**");
        assertThat(predicates.get("reservation-service")).contains("/api/v1/schedules/**", "/api/v1/admin/**");
        assertThat(predicates.get("payment-service")).contains("/api/v1/payments/**");
    }
}
