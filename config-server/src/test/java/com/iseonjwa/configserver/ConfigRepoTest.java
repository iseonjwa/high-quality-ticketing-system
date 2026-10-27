package com.iseonjwa.configserver;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.config.environment.Environment;
import org.springframework.cloud.config.environment.PropertySource;
import org.springframework.cloud.config.server.environment.EnvironmentRepository;

/**
 * 저장소의 config-repo/를 실제로 읽어 서비스가 받을 설정을 확인한다.
 * YAML 문법 오류나 프로필 덮어쓰기 순서가 깨지면 여기서 실패한다.
 */
@SpringBootTest(properties = {
        "spring.cloud.config.server.native.search-locations=file:../config-repo/",
        "management.tracing.export.zipkin.enabled=false"
})
class ConfigRepoTest {

    @Autowired
    EnvironmentRepository repository;

    @Test
    void 공통_설정은_compose_네트워크_주소를_준다() {
        Environment env = repository.findOne("user-service", "default", null);

        assertThat(firstValue(env, "spring.kafka.bootstrap-servers")).isEqualTo("kafka:29092");
        assertThat(firstValue(env, "eureka.client.service-url.defaultZone")).isEqualTo("http://eureka:8761/eureka/");
    }

    @Test
    void local_프로필은_localhost로_덮어쓴다() {
        Environment env = repository.findOne("user-service", "local", null);

        // 앞에 있는 소스가 우선한다. application-local.yml이 application.yml보다 앞이어야 한다
        assertThat(env.getPropertySources().getFirst().getName()).endsWith("application-local.yml");
        assertThat(firstValue(env, "spring.kafka.bootstrap-servers")).isEqualTo("localhost:9092");
    }

    @Test
    void loadtest_프로필은_추적_샘플링을_낮춘다() {
        Environment env = repository.findOne("user-service", "loadtest", null);

        assertThat(firstValue(env, "management.tracing.sampling.probability")).isEqualTo(0.1);
    }

    @Test
    void 설정에는_비밀값을_직접_쓰지_않는다() {
        for (String profile : new String[] {"default", "local", "loadtest", "prod"}) {
            Environment env = repository.findOne("user-service", profile, null);
            env.getPropertySources().forEach(source -> source.getSource().forEach((key, value) -> {
                String name = String.valueOf(key).toLowerCase();
                if (name.contains("password") || name.contains("secret")) {
                    assertThat(String.valueOf(value)).as(source.getName() + " " + key).startsWith("${");
                }
            }));
        }
    }

    private static Object firstValue(Environment env, String key) {
        return env.getPropertySources().stream()
                .map(PropertySource::getSource)
                .filter(source -> source.containsKey(key))
                .map(source -> source.get(key))
                .findFirst()
                .orElse(null);
    }
}
