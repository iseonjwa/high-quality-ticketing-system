package com.iseonjwa.common.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// iseonjwa.request-log.* 설정. enabled(필터 등록 여부)는 @ConditionalOnProperty가 따로 읽어 여기 두지 않는다.
// 키가 없으면 지금까지와 같은 ALL로 동작한다. 운영 기본값(PROBLEMS)은 config-repo에서 정한다.
@ConfigurationProperties("iseonjwa.request-log")
public record RequestLogProperties(RequestLogPolicy.Mode mode, Integer minStatus, Duration slowThreshold) {
    static final RequestLogPolicy.Mode DEFAULT_MODE = RequestLogPolicy.Mode.ALL;
    static final int DEFAULT_MIN_STATUS = 500;
    static final Duration DEFAULT_SLOW_THRESHOLD = Duration.ofMillis(200);

    public RequestLogProperties {
        mode = mode != null ? mode : DEFAULT_MODE;
        minStatus = minStatus != null ? minStatus : DEFAULT_MIN_STATUS;
        slowThreshold = slowThreshold != null ? slowThreshold : DEFAULT_SLOW_THRESHOLD;
    }

    public RequestLogPolicy policy() {
        return new RequestLogPolicy(mode, minStatus, slowThreshold);
    }
}
