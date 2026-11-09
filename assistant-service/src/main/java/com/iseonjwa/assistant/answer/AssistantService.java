package com.iseonjwa.assistant.answer;

import com.iseonjwa.assistant.answer.Answer.FallbackReason;
import com.iseonjwa.assistant.answer.Answer.Mode;
import com.iseonjwa.assistant.knowledge.KnowledgeSection;
import com.iseonjwa.assistant.limit.DailyBudget;
import com.iseonjwa.assistant.limit.UserRateLimiter;
import com.iseonjwa.assistant.llm.LlmClient;
import com.iseonjwa.assistant.llm.LlmUnavailableException;
import com.iseonjwa.assistant.retrieval.Retriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * 질문 하나에 답한다. LLM이 있으면 Claude가 문서를 근거로 답하고(AI), 없거나 못 받으면 검색 결과만 보인다(SEARCH).
 * LLM 쪽 어떤 실패도 오류로 내지 않고 SEARCH로 내려 간다.
 */
public class AssistantService {
    static final String SEARCH_ANSWER = "관련 안내를 찾았어요. 아래 내용을 확인해 주세요.";

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

    private final Retriever retriever;
    private final UserRateLimiter rateLimiter;
    private final DailyBudget dailyBudget;
    private final LlmClient llm;
    private final Semaphore concurrentCalls;

    /** @param llm 키가 없거나 꺼 두면 {@code null}이다 */
    public AssistantService(Retriever retriever, UserRateLimiter rateLimiter, DailyBudget dailyBudget, LlmClient llm,
            int maxConcurrentCalls) {
        this.retriever = retriever;
        this.rateLimiter = rateLimiter;
        this.dailyBudget = dailyBudget;
        this.llm = llm;
        this.concurrentCalls = new Semaphore(maxConcurrentCalls);
    }

    public Answer ask(long userId, String question) {
        var startedAt = System.nanoTime();
        rateLimiter.tryAcquire(userId).ifPresent(wait -> {
            log.info("event=ASSISTANT_RATE_LIMITED userId={} retryAfterMillis={}", userId, wait.toMillis());
            throw new RateLimitedException(wait);
        });

        var answer = answer(question);
        // Note: 질문 원문은 개인정보가 섞일 수 있어 남기지 않는다. 글자 수와 결과만 남긴다
        log.info("event=ASSISTANT_ANSWERED userId={} questionLength={} mode={} fallbackReason={} elapsedMillis={}",
                userId, question.length(), answer.mode(), answer.fallbackReason(),
                (System.nanoTime() - startedAt) / 1_000_000);
        return answer;
    }

    private Answer answer(String question) {
        if (llm == null) {
            return searchAnswer(question, FallbackReason.LLM_DISABLED);
        }
        // Note: 동시 호출 상한은 기다리지 않고 바로 검색 모드로 보낸다. 대기 스레드가 쌓이면 상담 때문에 서비스가 느려진다
        if (!concurrentCalls.tryAcquire()) {
            log.warn("event=ASSISTANT_LLM_BUSY");
            return searchAnswer(question, FallbackReason.LLM_UNAVAILABLE);
        }
        try {
            if (!dailyBudget.tryUse()) {
                log.warn("event=ASSISTANT_LLM_DAILY_LIMIT");
                return searchAnswer(question, FallbackReason.LLM_UNAVAILABLE);
            }
            return aiAnswer(question);
        } finally {
            concurrentCalls.release();
        }
    }

    private Answer aiAnswer(String question) {
        try {
            var result = llm.answer(question, retriever.forModel(question));
            return new Answer(result.text(), Mode.AI, null, sources(result.citedSections()));
        } catch (LlmUnavailableException exception) {
            return searchAnswer(question, FallbackReason.LLM_UNAVAILABLE);
        }
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
