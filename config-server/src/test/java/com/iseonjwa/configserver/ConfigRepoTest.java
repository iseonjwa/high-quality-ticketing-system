package com.iseonjwa.configserver;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.config.environment.Environment;
import org.springframework.cloud.config.environment.PropertySource;
import org.springframework.cloud.config.server.environment.EnvironmentRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 저장소의 config-repo 파일을 Config 서버가 실제로 읽어 내는지 확인한다.
 * YAML 문법이 깨지거나 파일 이름이 서비스 이름과 어긋나면 여기서 잡힌다.
 */
@SpringBootTest(properties = "spring.cloud.config.server.native.search-locations=${config.repo.location}")
class ConfigRepoTest {

    @Autowired
    EnvironmentRepository repository;

    @Test
    void gateway는_공통_설정과_자기_설정을_함께_받는다() {
        Map<String, Object> properties = merged(repository.findOne("gateway", "default", null));

        assertThat(properties)
                .containsEntry("server.port", 8080)
                .containsEntry("spring.cloud.gateway.server.webflux.routes[0].uri", "lb://user-service")
                .containsEntry("gateway.auth.public-paths[1]", "POST /api/v1/auth/login")
                .containsEntry("gateway.auth.jwt-secret", "${JWT_SECRET}")
                .containsKey("eureka.client.service-url.defaultZone");
    }

    @Test
    void user_service는_8081_포트를_받는다() {
        Map<String, Object> properties = merged(repository.findOne("user-service", "default", null));

        assertThat(properties).containsEntry("server.port", 8081);
    }

    @Test
    void loadtest_프로필은_추적_샘플링을_낮춘다() {
        Map<String, Object> properties = merged(repository.findOne("user-service", "loadtest", null));

        assertThat(properties).containsEntry("management.tracing.sampling.probability", 0.1);
    }

    /** 앞에 있는 프로퍼티 소스가 우선이다. 뒤에서부터 덮어써서 최종 값을 만든다. */
    private Map<String, Object> merged(Environment environment) {
        Map<String, Object> result = new HashMap<>();
        List<PropertySource> sources = environment.getPropertySources();
        for (int i = sources.size() - 1; i >= 0; i--) {
            sources.get(i).getSource().forEach((key, value) -> result.put(String.valueOf(key), value));
        }
        return result;
    }
}
