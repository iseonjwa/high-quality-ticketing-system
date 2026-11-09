package com.iseonjwa.assistant.answer;

import com.iseonjwa.assistant.answer.Answer.FallbackReason;
import com.iseonjwa.assistant.answer.Answer.Mode;
import com.iseonjwa.assistant.global.AssistantProperties;
import com.iseonjwa.assistant.knowledge.KnowledgeBase;
import com.iseonjwa.assistant.knowledge.KnowledgeSection;
import com.iseonjwa.assistant.limit.DailyBudget;
import com.iseonjwa.assistant.limit.UserRateLimiter;
import com.iseonjwa.assistant.llm.LlmClient;
import com.iseonjwa.assistant.llm.LlmUnavailableException;
import com.iseonjwa.assistant.retrieval.Retriever;
import com.iseonjwa.assistant.support.MutableClock;
import com.iseonjwa.assistant.support.Sections;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantServiceTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-10T00:00:00Z"));
    private final Retriever retriever = new Retriever(KnowledgeBase.loadFromClasspath(),
            new AssistantProperties.Retrieval(4, 3, 2.0));

    private AssistantService service(LlmClient llm, int dailyLimit, int maxConcurrent) {
        return new AssistantService(retriever, new UserRateLimiter(clock, 100, 1000),
                new DailyBudget(clock, dailyLimit), llm, maxConcurrent);
    }

    /** 부른 횟수와 받은 절을 기록하는 스텁. */
    private static final class StubLlm implements LlmClient {
        final AtomicInteger calls = new AtomicInteger();
        final List<List<KnowledgeSection>> receivedSections = new ArrayList<>();
        LlmAnswer reply;
        RuntimeException failure;

        @Override
        public LlmAnswer answer(String question, List<KnowledgeSection> sections) {
            calls.incrementAndGet();
            receivedSections.add(sections);
            if (failure != null) {
                throw failure;
            }
            return reply;
        }
    }

    @Test
    void LLM이_답하면_AI_모드로_인용한_절을_출처로_준다() {
        var llm = new StubLlm();
        llm.reply = new LlmClient.LlmAnswer("승인이 늦으면 자동으로 환불돼요.",
                List.of(Sections.byId("payment.auto-refund")));

        var answer = service(llm, 10, 4).ask(1L, "환불은 언제 되나요?");

        assertThat(answer.mode()).isEqualTo(Mode.AI);
        assertThat(answer.fallbackReason()).isNull();
        assertThat(answer.answer()).isEqualTo("승인이 늦으면 자동으로 환불돼요.");
        assertThat(answer.sources()).singleElement().satisfies(source -> {
            assertThat(source.id()).isEqualTo("payment.auto-refund");
            assertThat(source.title()).isEqualTo("결제 > 시간이 지난 뒤 승인된 결제");
            assertThat(source.excerpt()).isEqualTo(Sections.byId("payment.auto-refund").body());
        });
        // 모델에는 검색으로 고른 상위 절을 넘긴다
        assertThat(llm.receivedSections.get(0)).hasSize(4);
    }

    @Test
    void 인용이_없는_AI_답이면_출처가_빈_목록이다() {
        var llm = new StubLlm();
        llm.reply = new LlmClient.LlmAnswer("문서에 없는 내용이라 공연 상세를 확인해 주세요.", List.of());

        var answer = service(llm, 10, 4).ask(1L, "굿즈는 어디서 사요?");

        assertThat(answer.mode()).isEqualTo(Mode.AI);
        assertThat(answer.sources()).isEmpty();
    }

    @Test
    void LLM_빈이_없으면_검색_모드_LLM_DISABLED로_답한다() {
        var answer = service(null, 10, 4).ask(1L, "환불은 언제 되나요?");

        assertThat(answer.mode()).isEqualTo(Mode.SEARCH);
        assertThat(answer.fallbackReason()).isEqualTo(FallbackReason.LLM_DISABLED);
        assertThat(answer.answer()).isEqualTo(AssistantService.SEARCH_ANSWER);
        assertThat(answer.sources()).hasSize(3);
    }

    @Test
    void LLM이_실패하면_검색_모드_LLM_UNAVAILABLE로_강등한다() {
        var llm = new StubLlm();
        llm.failure = new LlmUnavailableException("시한 초과");

        var answer = service(llm, 10, 4).ask(1L, "환불은 언제 되나요?");

        assertThat(llm.calls).hasValue(1);
        assertThat(answer.mode()).isEqualTo(Mode.SEARCH);
        assertThat(answer.fallbackReason()).isEqualTo(FallbackReason.LLM_UNAVAILABLE);
        assertThat(answer.sources()).hasSize(3);
    }

    @Test
    void 일일_전체_상한을_넘으면_LLM을_부르지_않고_검색_모드로_답한다() {
        var llm = new StubLlm();
        llm.reply = new LlmClient.LlmAnswer("답이에요.", List.of());
        var service = service(llm, 2, 4);

        service.ask(1L, "오픈 시각이 언제예요?");
        service.ask(1L, "오픈 시각이 언제예요?");
        var third = service.ask(1L, "오픈 시각이 언제예요?");

        assertThat(llm.calls).hasValue(2);
        assertThat(third.mode()).isEqualTo(Mode.SEARCH);
        assertThat(third.fallbackReason()).isEqualTo(FallbackReason.LLM_UNAVAILABLE);
    }

    @Test
    void 동시_호출_상한을_넘으면_기다리지_않고_검색_모드로_답한다() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        LlmClient slow = (question, sections) -> {
            calls.incrementAndGet();
            entered.countDown();
            await(release);
            return new LlmClient.LlmAnswer("느린 답이에요.", List.of());
        };
        var service = service(slow, 10, 1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> service.ask(1L, "오픈 시각이 언제예요?"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            var second = service.ask(2L, "오픈 시각이 언제예요?");

            assertThat(second.mode()).isEqualTo(Mode.SEARCH);
            assertThat(second.fallbackReason()).isEqualTo(FallbackReason.LLM_UNAVAILABLE);
            assertThat(calls).hasValue(1);

            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).mode()).isEqualTo(Mode.AI);
            // 끝나면 자리가 돌아와 다음 호출은 다시 LLM으로 간다
            assertThat(service.ask(3L, "오픈 시각이 언제예요?").mode()).isEqualTo(Mode.AI);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void LLM이_예상_밖_예외를_던지면_자리를_돌려주고_예외를_그대로_올린다() {
        var llm = new StubLlm();
        llm.failure = new IllegalStateException("버그");
        var service = service(llm, 10, 1);

        assertThatThrownBy(() -> service.ask(1L, "오픈 시각이 언제예요?")).isInstanceOf(IllegalStateException.class);
        llm.failure = null;
        llm.reply = new LlmClient.LlmAnswer("답이에요.", List.of());

        assertThat(service.ask(1L, "오픈 시각이 언제예요?").mode()).isEqualTo(Mode.AI);
    }

    @Test
    void 사용자_요청_상한을_넘으면_LLM을_부르기_전에_거절한다() {
        var llm = new StubLlm();
        llm.reply = new LlmClient.LlmAnswer("답이에요.", List.of());
        var service = new AssistantService(retriever, new UserRateLimiter(clock, 1, 10),
                new DailyBudget(clock, 10), llm, 4);
        service.ask(1L, "오픈 시각이 언제예요?");

        assertThatThrownBy(() -> service.ask(1L, "오픈 시각이 언제예요?"))
                .isInstanceOfSatisfying(RateLimitedException.class,
                        exception -> assertThat(exception.retryAfterSeconds()).isBetween(1L, 60L));
        assertThat(llm.calls).hasValue(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
