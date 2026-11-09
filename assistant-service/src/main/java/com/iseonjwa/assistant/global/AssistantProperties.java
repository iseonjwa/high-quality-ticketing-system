package com.iseonjwa.assistant.global;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 상담 설정. 값은 config-repo/assistant-service.yml에 있고, 설정 서버 없이 뜨는 test 프로필을 위해 같은 기본값을 둔다.
 */
@ConfigurationProperties(prefix = "assistant")
public record AssistantProperties(@DefaultValue Llm llm, @DefaultValue Retrieval retrieval,
                                  @DefaultValue RateLimit rateLimit) {

    public record Llm(@DefaultValue("true") boolean enabled, @DefaultValue("claude-haiku-5-5") String model,
                      @DefaultValue("1024") int maxTokens, @DefaultValue("8s") Duration timeout,
                      @DefaultValue("4") int maxConcurrent, @DefaultValue("1000") int dailyLimit,
                      @DefaultValue("8s") Duration streamFirstDeltaTimeout,
                      @DefaultValue("20s") Duration streamTimeout) {
    }

    public record Retrieval(@DefaultValue("4") int topK, @DefaultValue("3") int searchResultCount,
                            @DefaultValue("2.0") double minScore) {
    }

    public record RateLimit(@DefaultValue("5") int perMinute, @DefaultValue("30") int perDay) {
    }
}
