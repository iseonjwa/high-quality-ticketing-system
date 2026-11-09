package com.iseonjwa.gateway;

import org.junit.jupiter.api.Test;
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
    private GatewayProperties load() throws IOException {
        var environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("gateway", new FileSystemResource("../config-repo/gateway.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        return new Binder(ConfigurationPropertySources.get(environment))
                .bind("spring.cloud.gateway.server.webflux", GatewayProperties.class).get();
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
}
