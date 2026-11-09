package com.iseonjwa.assistant.global;

import com.iseonjwa.assistant.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 키가 없거나 꺼 두면 Claude 클라이언트 빈 자체를 만들지 않는다. */
class AnthropicConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AnthropicConfiguration.class)
            .withBean(AssistantProperties.class, () -> new AssistantProperties(
                    new AssistantProperties.Llm(true, "claude-haiku-5-5", 1024, java.time.Duration.ofSeconds(8), 4, 1000,
                            java.time.Duration.ofSeconds(8), java.time.Duration.ofSeconds(20)),
                    new AssistantProperties.Retrieval(4, 3, 2.0), new AssistantProperties.RateLimit(5, 30)));

    // Note: 실제 키처럼 보이는 문자열을 저장소에 두지 않으려고 실행할 때마다 무작위 값을 만든다
    private static String randomKey() {
        return UUID.randomUUID().toString();
    }

    @Test
    void 키가_비어_있으면_LlmClient_빈이_없다() {
        runner.withPropertyValues("anthropic.api-key=").run(context -> assertThat(context).doesNotHaveBean(LlmClient.class));
    }

    @Test
    void 키_속성이_아예_없어도_LlmClient_빈이_없다() {
        runner.run(context -> assertThat(context).doesNotHaveBean(LlmClient.class));
    }

    @Test
    void 키가_공백뿐이어도_LlmClient_빈이_없다() {
        runner.withPropertyValues("anthropic.api-key=   ")
                .run(context -> assertThat(context).doesNotHaveBean(LlmClient.class));
    }

    @Test
    void enabled가_false면_키가_있어도_LlmClient_빈이_없다() {
        runner.withPropertyValues("anthropic.api-key=" + randomKey(), "assistant.llm.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(LlmClient.class));
    }

    @Test
    void 키가_있고_켜져_있으면_LlmClient_빈이_있다() {
        runner.withPropertyValues("anthropic.api-key=" + randomKey())
                .run(context -> assertThat(context).hasSingleBean(LlmClient.class));
    }
}
