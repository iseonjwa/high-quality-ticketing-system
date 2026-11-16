package com.iseonjwa.common.web;

import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommonApiDocsConfigurationTest {
    private final CommonApiDocsConfiguration configuration = new CommonApiDocsConfiguration();

    // 서버 주소가 서비스 자신의 주소면 Try it out이 Gateway를 건너뛰어 사용자 헤더 없이 서비스에 닿는다
    @Test
    void 문서의_서버_주소는_Gateway를_거치도록_같은_주소의_루트다() {
        var openApi = configuration.commonOpenApi("user-service");

        assertThat(openApi.getInfo().getTitle()).isEqualTo("user-service");
        assertThat(openApi.getServers()).singleElement()
                .satisfies(server -> assertThat(server.getUrl()).isEqualTo("/"));
    }

    @Test
    void 모든_API에_JWT_Bearer_인증을_건다() {
        var openApi = configuration.commonOpenApi("user-service");

        var scheme = openApi.getComponents().getSecuritySchemes().get(CommonApiDocsConfiguration.BEARER_SCHEME);
        assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.HTTP);
        assertThat(scheme.getScheme()).isEqualTo("bearer");
        assertThat(openApi.getSecurity()).singleElement()
                .satisfies(requirement -> assertThat(requirement).containsKey(CommonApiDocsConfiguration.BEARER_SCHEME));
    }
}
