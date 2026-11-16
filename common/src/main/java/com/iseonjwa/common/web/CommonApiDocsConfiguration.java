package com.iseonjwa.common.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

// 서비스 API 문서(/v3/api-docs)의 공통 머리말이다. 화면(Swagger UI)은 Gateway의 /api/docs에 하나만 있다(ADR 0063).
// springdoc을 넣은 서비스에서만 켜진다. 클래스 이름 문자열로 검사해 springdoc이 없는 서비스도 이 설정을 읽다 멈추지 않는다
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "io.swagger.v3.oas.models.OpenAPI")
public class CommonApiDocsConfiguration {
    static final String BEARER_SCHEME = "bearer";

    // Note: 서버 주소를 "/"로 둔다. 문서는 Gateway를 거쳐 열리므로 Try it out도 같은 주소의 Gateway로 보내야
    // 로그인 확인과 사용자 헤더 주입을 거친다. 비워 두면 springdoc이 서비스 자신의 주소(내부 포트)를 적는다
    @Bean
    OpenAPI commonOpenApi(@Value("${spring.application.name}") String applicationName) {
        return new OpenAPI()
                .info(new Info().title(applicationName).version("v1"))
                .servers(List.of(new Server().url("/").description("Gateway")))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                // 공개 API에도 붙지만 토큰이 있을 때 함께 보낼 뿐이다. 공개 여부는 Gateway의 AccessPolicy가 정한다
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
