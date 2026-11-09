package com.iseonjwa.assistant.global;

import com.iseonjwa.assistant.answer.AssistantService;
import com.iseonjwa.assistant.knowledge.KnowledgeBase;
import com.iseonjwa.assistant.limit.DailyBudget;
import com.iseonjwa.assistant.limit.UserRateLimiter;
import com.iseonjwa.assistant.llm.LlmClient;
import com.iseonjwa.assistant.retrieval.Retriever;
import com.iseonjwa.common.web.CommonWebConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.time.ZoneId;

@Configuration(proxyBeanMethods = false)
@Import({CommonWebConfiguration.class, AnthropicConfiguration.class})
@EnableConfigurationProperties(AssistantProperties.class)
class AssistantConfiguration {
    @Bean
    Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }

    @Bean
    KnowledgeBase knowledgeBase() {
        return KnowledgeBase.loadFromClasspath();
    }

    @Bean
    Retriever retriever(KnowledgeBase knowledgeBase, AssistantProperties properties) {
        return new Retriever(knowledgeBase, properties.retrieval());
    }

    @Bean
    UserRateLimiter userRateLimiter(Clock clock, AssistantProperties properties) {
        return new UserRateLimiter(clock, properties.rateLimit().perMinute(), properties.rateLimit().perDay());
    }

    @Bean
    DailyBudget dailyBudget(Clock clock, AssistantProperties properties) {
        return new DailyBudget(clock, properties.llm().dailyLimit());
    }

    @Bean
    AssistantService assistantService(Retriever retriever, UserRateLimiter rateLimiter, DailyBudget dailyBudget,
            ObjectProvider<LlmClient> llm, AssistantProperties properties) {
        return new AssistantService(retriever, rateLimiter, dailyBudget, llm.getIfAvailable(),
                properties.llm().maxConcurrent());
    }
}
