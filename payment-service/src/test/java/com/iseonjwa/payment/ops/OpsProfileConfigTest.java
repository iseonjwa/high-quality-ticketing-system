package com.iseonjwa.payment.ops;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code application.yml}의 {@code on-profile: ops} 문서가 명령 실행에 필요 없는 것을 모두 끄는지, 설정 서버(config-repo)가
 * 같은 키를 켜서 덮어쓰지 않는지 파일로 확인한다.
 */
class OpsProfileConfigTest {
    private static final Map<String, Object> EXPECTED = Map.of(
            "spring.main.web-application-type", "none",
            "spring.kafka.listener.auto-startup", false,
            "eureka.client.enabled", false,
            "messaging.outbox.relay-enabled", false,
            "payment.recheck.enabled", false,
            "payment.refund-worker.enabled", false,
            "payment.housekeeping.enabled", false,
            "payment.metrics.gauge-refresh.enabled", false);

    @Test
    void ops_문서가_웹_서버와_배경_실행기와_Eureka를_끈다() throws IOException {
        var ops = opsDocument();
        EXPECTED.forEach((key, value) -> assertEquals(value, ops.getProperty(key), key));
    }

    @Test
    void 설정_서버가_ops_문서의_키를_설정하지_않는다() throws IOException {
        var configured = new ArrayList<String>();
        for (var file : List.of("application.yml", "application-local.yml", "application-prod.yml",
                "application-loadtest.yml", "payment-service.yml")) {
            var resource = new FileSystemResource("../config-repo/" + file);
            assertTrue(resource.exists(), file);
            for (var source : load(resource)) {
                for (var name : ((EnumerablePropertySource<?>) source).getPropertyNames()) {
                    if (EXPECTED.containsKey(name)) configured.add(file + ": " + name);
                }
            }
        }
        // 설정 서버 값은 로컬 application.yml보다 우선한다. 같은 키가 있으면 ops 프로필에서 다시 켜질 수 있다
        assertEquals(List.of(), configured);
    }

    private PropertySource<?> opsDocument() throws IOException {
        for (var source : load(new ClassPathResource("application.yml"))) {
            if ("ops".equals(source.getProperty("spring.config.activate.on-profile"))) return source;
        }
        assertNotNull(null, "application.yml에 on-profile: ops 문서가 없다");
        return null;
    }

    private List<PropertySource<?>> load(Resource resource) throws IOException {
        return new YamlPropertySourceLoader().load(resource.getFilename(), resource);
    }
}
