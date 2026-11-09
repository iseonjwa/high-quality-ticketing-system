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
import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * 스트리밍 답변을 준비한다. 사용자 요청 제한은 여기서 바로 검사해(SSE를 열기 전에 429로 끝낼 수 있게),
     * 통과하면 이벤트를 흘려보내는 작업을 돌려준다. 작업은 호출한 쪽이 별도 스레드에서 실행한다.
     */
    public StreamingAnswer prepareStream(long userId, String question) {
        rateLimiter.tryAcquire(userId).ifPresent(wait -> {
            log.info("event=ASSISTANT_RATE_LIMITED userId={} stream=true retryAfterMillis={}", userId, wait.toMillis());
            throw new RateLimitedException(wait);
        });
        return sink -> {
            var startedAt = System.nanoTime();
            var outcome = streamTo(question, sink);
            // Note: 질문 원문은 개인정보가 섞일 수 있어 남기지 않는다. 글자 수와 결과만 남긴다
            log.info("event=ASSISTANT_ANSWERED stream=true userId={} questionLength={} mode={} fallbackReason={} elapsedMillis={}",
                    userId, question.length(), outcome.mode(), outcome.fallbackReason(),
                    (System.nanoTime() - startedAt) / 1_000_000);
        };
    }

    /** 스트리밍 답변 한 건을 {@link StreamSink}로 흘려보내는 작업. */
    @FunctionalInterface
    public interface StreamingAnswer {
        void run(StreamSink sink);
    }

    private record Outcome(Mode mode, FallbackReason fallbackReason) {
    }

    private Outcome streamTo(String question, StreamSink sink) {
        try {
            if (llm == null) {
                return streamSearch(question, FallbackReason.LLM_DISABLED, false, sink);
            }
            // Note: JSON 경로와 같은 세마포어·예산을 쓴다. 세마포어는 스트림이 끝날 때까지 쥔다
            if (!concurrentCalls.tryAcquire()) {
                log.warn("event=ASSISTANT_LLM_BUSY stream=true");
                return streamSearch(question, FallbackReason.LLM_UNAVAILABLE, false, sink);
            }
            try {
                if (!dailyBudget.tryUse()) {
                    log.warn("event=ASSISTANT_LLM_DAILY_LIMIT stream=true");
                    return streamSearch(question, FallbackReason.LLM_UNAVAILABLE, false, sink);
                }
                return streamAi(question, sink);
            } finally {
                concurrentCalls.release();
            }
        } catch (StreamSink.ClientDisconnectedException exception) {
            log.info("event=ASSISTANT_STREAM_CLIENT_GONE");
            return new Outcome(Mode.AI, null);
        }
    }

    private Outcome streamAi(String question, StreamSink sink) {
        var text = new StringBuilder();
        var started = new AtomicBoolean();
        try {
            var result = llm.stream(question, retriever.forModel(question), chunk -> {
                // Note: meta(AI)는 첫 글자 조각 직전에 보낸다. 그 전에 실패하면 meta를 SEARCH로 보내야 하기 때문이다
                if (started.compareAndSet(false, true)) {
                    sink.meta(Mode.AI, null);
                }
                text.append(chunk);
                sink.delta(chunk);
            });
            if (started.compareAndSet(false, true)) {
                // 조각 없이 최종 답만 돌려준 구현도 받아 준다
                sink.meta(Mode.AI, null);
                sink.delta(result.text());
                text.append(result.text());
            }
            sink.sources(sources(result.citedSections()));
            sink.done(text.toString(), Mode.AI, null);
            return new Outcome(Mode.AI, null);
        } catch (StreamSink.ClientDisconnectedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // Note: 스트림이 열린 뒤에는 HTTP 상태를 바꿀 수 없어 예상 밖 예외도 오류로 내지 않고 검색 모드로 마무리한다
            if (!(exception instanceof LlmUnavailableException)) {
                log.error("event=ASSISTANT_STREAM_FAILED cause={}", exception.getClass().getSimpleName());
            }
            return streamSearch(question, FallbackReason.LLM_UNAVAILABLE, started.get(), sink);
        }
    }

    private Outcome streamSearch(String question, FallbackReason reason, boolean afterDelta, StreamSink sink) {
        if (afterDelta) {
            sink.fallback(reason);
        } else {
            sink.meta(Mode.SEARCH, reason);
        }
        sink.sources(sources(retriever.forSearch(question)));
        sink.done(SEARCH_ANSWER, Mode.SEARCH, reason);
        return new Outcome(Mode.SEARCH, reason);
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
