package com.iseonjwa.assistant.global;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.iseonjwa.assistant.llm.AnthropicLlmClient;
import com.iseonjwa.assistant.llm.LlmClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Claude 클라이언트. 키가 비었거나 {@code assistant.llm.enabled=false}면 빈을 만들지 않고, 그때는 검색 모드로만 답한다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${assistant.llm.enabled:true} and !'${anthropic.api-key:}'.isBlank()")
class AnthropicConfiguration {
    @Bean(destroyMethod = "close")
    AnthropicClient anthropicClient(@Value("${anthropic.api-key}") String apiKey,
            @Value("${anthropic.base-url:}") String baseUrl, AssistantProperties properties) {
        // Note: SDK 기본값은 시한 10분·재시도 2회다. 사용자는 Gateway 10초 안에 답을 받아야 하므로 8초·재시도 0회로 바꾼다
        var builder = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(properties.llm().timeout())
                .maxRetries(0);
        if (!baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }

    @Bean
    LlmClient llmClient(AnthropicClient client, AssistantProperties properties) {
        var llm = properties.llm();
        return new AnthropicLlmClient(client, llm.model(), llm.maxTokens(), llm.streamFirstDeltaTimeout(),
                llm.streamTimeout());
    }
}
