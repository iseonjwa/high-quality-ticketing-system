package iseonjwa.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.config.environment.Environment;
import org.springframework.cloud.config.environment.PropertySource;
import org.springframework.cloud.config.server.environment.EnvironmentRepository;

/**
 * 저장소의 config-repo/를 실제로 읽어 서비스가 받을 설정을 확인한다.
 * YAML 문법 오류, 프로필 덮어쓰기 순서, 비밀값 직접 기재를 여기서 잡는다.
 */
@SpringBootTest(properties = {
        "spring.cloud.config.server.native.search-locations=file:../config-repo/",
        "management.tracing.export.zipkin.enabled=false"
})
class ConfigRepoTest {
    private static final List<String> SERVICES = List.of(
            "gateway", "eureka", "user-service", "reservation-service", "payment-service", "mock-pg");
    private static final List<String> PROFILES = List.of("default", "local", "loadtest", "prod", "test");

    @Autowired
    EnvironmentRepository repository;

    @Test
    void 프로필_파일이_서비스_파일보다_먼저_적용된다() {
        Environment env = repository.findOne("user-service", "local", null);

        // Note: 앞에 있는 소스가 우선한다. {서비스}-{프로필} > application-{프로필} > {서비스} > application
        assertThat(env.getPropertySources()).extracting(PropertySource::getName)
                .map(name -> name.substring(name.lastIndexOf('/') + 1))
                .containsExactly("application-local.yml", "user-service.yml", "application.yml");
        assertThat(firstValue(env, "management.tracing.sampling.probability")).isEqualTo(1.0);
    }

    @Test
    void loadtest_프로필은_추적_샘플링을_낮춘다() {
        Environment env = repository.findOne("reservation-service", "loadtest", null);

        assertThat(firstValue(env, "management.tracing.sampling.probability")).isEqualTo(0.01);
    }

    @Test
    void 설정에는_비밀값을_직접_쓰지_않는다() {
        for (String service : SERVICES) {
            for (String profile : PROFILES) {
                Environment env = repository.findOne(service, profile, null);
                env.getPropertySources().forEach(source -> source.getSource().forEach((key, value) -> {
                    String name = String.valueOf(key).toLowerCase();
                    if (name.contains("password") || name.contains("secret")) {
                        assertThat(String.valueOf(value)).as(source.getName() + " " + key).startsWith("${");
                    }
                }));
            }
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
