package com.iseonjwa.assistant.answer;

import com.iseonjwa.assistant.answer.Answer.FallbackReason;
import com.iseonjwa.assistant.answer.Answer.Mode;
import com.iseonjwa.assistant.knowledge.KnowledgeSection;
import com.iseonjwa.assistant.limit.UserRateLimiter;
import com.iseonjwa.assistant.retrieval.Retriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class AssistantService {
    static final String SEARCH_ANSWER = "관련 안내를 찾았어요. 아래 내용을 확인해 주세요.";

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

    private final Retriever retriever;
    private final UserRateLimiter rateLimiter;

    public AssistantService(Retriever retriever, UserRateLimiter rateLimiter) {
        this.retriever = retriever;
        this.rateLimiter = rateLimiter;
    }

    public Answer ask(long userId, String question) {
        var startedAt = System.nanoTime();
        rateLimiter.tryAcquire(userId).ifPresent(wait -> {
            log.info("event=ASSISTANT_RATE_LIMITED userId={} retryAfterMillis={}", userId, wait.toMillis());
            throw new RateLimitedException(wait);
        });

        var answer = searchAnswer(question, FallbackReason.LLM_DISABLED);
        // Note: 질문 원문은 개인정보가 섞일 수 있어 남기지 않는다. 글자 수와 결과만 남긴다
        log.info("event=ASSISTANT_ANSWERED userId={} questionLength={} mode={} fallbackReason={} elapsedMillis={}",
                userId, question.length(), answer.mode(), answer.fallbackReason(),
                (System.nanoTime() - startedAt) / 1_000_000);
        return answer;
    }

    private Answer searchAnswer(String question, FallbackReason reason) {
        return new Answer(SEARCH_ANSWER, Mode.SEARCH, reason, sources(retriever.forSearch(question)));
    }

    private static List<Answer.Source> sources(List<KnowledgeSection> sections) {
        return sections.stream()
                .map(section -> new Answer.Source(section.id(), section.displayTitle(), section.body()))
                .toList();
    }
}
