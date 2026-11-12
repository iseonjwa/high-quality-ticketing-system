package com.iseonjwa.common.web;

import com.iseonjwa.common.web.RequestLogPolicy.Mode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RequestLogPropertiesTest {
    private static RequestLogProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("iseonjwa.request-log", RequestLogProperties.class);
    }

    @Test
    void 키가_없으면_지금까지처럼_ALL이고_기준은_500과_200ms다() {
        var properties = bind(Map.of("iseonjwa.request-log.enabled", "true"));

        assertThat(properties.mode()).isEqualTo(Mode.ALL);
        assertThat(properties.minStatus()).isEqualTo(500);
        assertThat(properties.slowThreshold()).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    void 설정_파일의_소문자_값과_시간_문자열을_읽는다() {
        var properties = bind(Map.of(
                "iseonjwa.request-log.mode", "problems",
                "iseonjwa.request-log.min-status", "400",
                "iseonjwa.request-log.slow-threshold", "150ms"));

        assertThat(properties.policy()).isEqualTo(new RequestLogPolicy(Mode.PROBLEMS, 400, Duration.ofMillis(150)));
    }
}
