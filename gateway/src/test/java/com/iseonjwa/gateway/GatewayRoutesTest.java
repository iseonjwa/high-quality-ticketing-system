package com.iseonjwa.gateway;

import org.junit.jupiter.api.Test;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/** config-repo/gateway.yml의 라우트가 Gateway 설정 모양으로 읽히는지 확인한다. */
class GatewayRoutesTest {
    private Binder binder() throws IOException {
        var environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("gateway", new FileSystemResource("../config-repo/gateway.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        return new Binder(ConfigurationPropertySources.get(environment));
    }

    private GatewayProperties load() throws IOException {
        return binder().bind("spring.cloud.gateway.server.webflux", GatewayProperties.class).get();
    }

    @Test
    void 상담_라우트만_응답_시한_10초와_연결_시한_1초를_라우트에_둔다() throws IOException {
        var routes = load().getRoutes();

        var assistant = routes.stream().filter(route -> route.getId().equals("assistant")).findFirst().orElseThrow();
        assertThat(assistant.getUri().toString()).isEqualTo("lb://assistant-service");
        assertThat(assistant.getPredicates()).singleElement()
                .satisfies(predicate -> assertThat(predicate.getArgs().values()).containsExactly("/api/v1/assistant/**"));
        assertThat(assistant.getMetadata()).containsEntry("response-timeout", 10000).containsEntry("connect-timeout", 1000);

        // 다른 라우트는 전역 시한(3초)을 그대로 쓴다
        assertThat(routes.stream().filter(route -> !route.getId().equals("assistant")))
                .allSatisfy(route -> assertThat(route.getMetadata()).doesNotContainKey("response-timeout"));
    }

    // Swagger UI가 고르는 문서 주소마다 그 서비스로 가는 라우트가 있어야 한다. 하나라도 빠지면 화면에서 그 문서만 404다
    @Test
    void Swagger_UI의_문서_주소마다_서비스의_문서로_가는_라우트가_있다() throws IOException {
        var routes = load().getRoutes();
        var urls = binder().bind("springdoc.swagger-ui", SwaggerUiConfigProperties.class).get().getUrls();

        assertThat(urls).extracting(url -> url.getName()).containsExactlyInAnyOrder(
                "user-service", "reservation-service", "payment-service", "assistant-service");
        assertThat(urls).allSatisfy(url -> {
            var route = routes.stream().filter(candidate -> candidate.getId().equals(url.getName() + "-api-docs"))
                    .findFirst().orElseThrow();
            assertThat(route.getUri().toString()).isEqualTo("lb://" + url.getName());
            assertThat(route.getPredicates()).singleElement()
                    .satisfies(predicate -> assertThat(predicate.getArgs().values()).containsExactly(url.getUrl()));
            assertThat(route.getFilters()).singleElement()
                    .satisfies(filter -> assertThat(filter.getArgs().values()).containsExactly("/v3/api-docs"));
        });
    }
}
