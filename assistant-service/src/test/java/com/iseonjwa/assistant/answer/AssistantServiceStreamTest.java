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
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 스트리밍 경로의 이벤트 순서와 강등 규칙을 고정한다. 이벤트 계약은 docs/design/assistant/README.md 2-1절이다. */
class AssistantServiceStreamTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-10T00:00:00Z"));
    private final Retriever retriever = new Retriever(KnowledgeBase.loadFromClasspath(),
            new AssistantProperties.Retrieval(4, 3, 2.0));

    private AssistantService service(LlmClient llm, int dailyLimit, int maxConcurrent) {
        return new AssistantService(retriever, new UserRateLimiter(clock, 100, 1000),
                new DailyBudget(clock, dailyLimit), llm, maxConcurrent);
    }

    /** 이벤트 이름과 내용을 순서대로 기록한다. */
    private static final class RecordingSink implements StreamSink {
        final List<String> names = new ArrayList<>();
        final List<String> deltas = new ArrayList<>();
        Mode metaMode;
        FallbackReason metaReason;
        FallbackReason fallbackReason;
        List<Answer.Source> sources;
        String doneAnswer;
        Mode doneMode;
        FallbackReason doneReason;
        /** 이 번째 이벤트를 쓰려 할 때 연결이 끊긴 것으로 친다(0이면 끊기지 않음). */
        int disconnectAtEvent;

        private void record(String name) {
            if (disconnectAtEvent > 0 && names.size() + 1 == disconnectAtEvent) {
                throw new ClientDisconnectedException(null);
            }
            names.add(name);
        }

        @Override
        public void meta(Mode mode, FallbackReason reason) {
            record("meta");
            metaMode = mode;
            metaReason = reason;
        }

        @Override
        public void delta(String text) {
            record("delta");
            deltas.add(text);
        }

        @Override
        public void fallback(FallbackReason reason) {
            record("fallback");
            fallbackReason = reason;
        }

        @Override
        public void sources(List<Answer.Source> sources) {
            record("sources");
            this.sources = sources;
        }

        @Override
        public void done(String answer, Mode mode, FallbackReason reason) {
            record("done");
            doneAnswer = answer;
            doneMode = mode;
            doneReason = reason;
        }
    }

    /** 부른 횟수를 세고, 정해 둔 동작을 하는 스텁. */
    private static final class StubLlm implements LlmClient {
        final AtomicInteger streamCalls = new AtomicInteger();
        List<String> chunks = List.of();
        List<KnowledgeSection> cited = List.of();
        RuntimeException failureAfterChunks;

        @Override
        public LlmAnswer answer(String question, List<KnowledgeSection> sections) {
            return new LlmAnswer("JSON 답이에요.", List.of());
        }

        @Override
        public LlmAnswer stream(String question, List<KnowledgeSection> sections, Consumer<String> onDelta) {
            streamCalls.incrementAndGet();
            chunks.forEach(onDelta);
            if (failureAfterChunks != null) {
                throw failureAfterChunks;
            }
            return new LlmAnswer(String.join("", chunks).strip(), cited);
        }
    }

    private static void run(AssistantService.StreamingAnswer streaming, StreamSink sink) {
        streaming.run(sink);
    }

    @Test
    void 정상이면_meta_AI_delta들_sources_done_순서이고_done은_조각을_이어_붙인_값이다() {
        var llm = new StubLlm();
        llm.chunks = List.of("시간이 지난 뒤 ", "승인되면 ", "자동으로 환불돼요.");
        llm.cited = List.of(Sections.byId("payment.auto-refund"));
        var sink = new RecordingSink();

        run(service(llm, 10, 4).prepareStream(1L, "환불은 언제 되나요?"), sink);

        assertThat(sink.names).containsExactly("meta", "delta", "delta", "delta", "sources", "done");
        assertThat(sink.metaMode).isEqualTo(Mode.AI);
        assertThat(sink.metaReason).isNull();
        assertThat(sink.deltas).containsExactly("시간이 지난 뒤 ", "승인되면 ", "자동으로 환불돼요.");
        assertThat(sink.sources).extracting(Answer.Source::id).containsExactly("payment.auto-refund");
        assertThat(sink.doneAnswer).isEqualTo(String.join("", sink.deltas));
        assertThat(sink.doneMode).isEqualTo(Mode.AI);
        assertThat(sink.doneReason).isNull();
    }

    @Test
    void 조각_뒤에_실패하면_fallback_sources_done_SEARCH로_마무리한다() {
        var llm = new StubLlm();
        llm.chunks = List.of("답하던 ", "중에");
        llm.failureAfterChunks = new LlmUnavailableException("시한 초과");
        var sink = new RecordingSink();

        run(service(llm, 10, 4).prepareStream(1L, "환불은 언제 되나요?"), sink);

        assertThat(sink.names).containsExactly("meta", "delta", "delta", "fallback", "sources", "done");
        assertThat(sink.fallbackReason).isEqualTo(FallbackReason.LLM_UNAVAILABLE);
        assertThat(sink.sources).hasSize(3);
        assertThat(sink.doneAnswer).isEqualTo(AssistantService.SEARCH_ANSWER);
        assertThat(sink.doneMode).isEqualTo(Mode.SEARCH);
        assertThat(sink.doneReason).isEqualTo(FallbackReason.LLM_UNAVAILABLE);
    }

    @Test
    void 조각_뒤_예상_밖_예외도_오류로_내지_않고_fallback으로_마무리한다() {
        var llm = new StubLlm();
        llm.chunks = List.of("답");
        llm.failureAfterChunks = new IllegalStateException("버그");
        var sink = new RecordingSink();

        run(service(llm, 10, 4).prepareStream(1L, "환불은 언제 되나요?"), sink);

        assertThat(sink.names).containsExactly("meta", "delta", "fallback", "sources", "done");
    }

    @Test
    void 첫_조각_전에_실패하면_meta_SEARCH_sources_done이고_fallback은_없다() {
        var llm = new StubLlm();
        llm.failureAfterChunks = new LlmUnavailableException("연결 실패");
        var sink = new RecordingSink();

        run(service(llm, 10, 4).prepareStream(1L, "환불은 언제 되나요?"), sink);

        assertThat(sink.names).containsExactly("meta", "sources", "done");
        assertThat(sink.metaMode).isEqualTo(Mode.SEARCH);
        assertThat(sink.metaReason).isEqualTo(FallbackReason.LLM_UNAVAILABLE);
        assertThat(sink.sources).hasSize(3);
        assertThat(sink.doneMode).isEqualTo(Mode.SEARCH);
        assertThat(sink.doneAnswer).isEqualTo(AssistantService.SEARCH_ANSWER);
    }

    @Test
    void 키가_없으면_LLM을_부르지_않고_meta_SEARCH_LLM_DISABLED로_답한다() {
        var sink = new RecordingSink();

        run(service(null, 10, 4).prepareStream(1L, "환불은 언제 되나요?"), sink);

        assertThat(sink.names).containsExactly("meta", "sources", "done");
        assertThat(sink.metaReason).isEqualTo(FallbackReason.LLM_DISABLED);
        assertThat(sink.doneReason).isEqualTo(FallbackReason.LLM_DISABLED);
    }

    @Test
    void 일일_예산을_넘으면_LLM을_부르지_않고_검색_모드로_답한다() {
        var llm = new StubLlm();
        llm.chunks = List.of("답이에요.");
        var service = service(llm, 1, 4);
        run(service.prepareStream(1L, "오픈 시각이 언제예요?"), new RecordingSink());
        var second = new RecordingSink();

        run(service.prepareStream(1L, "오픈 시각이 언제예요?"), second);

        assertThat(llm.streamCalls).hasValue(1);
        assertThat(second.names).containsExactly("meta", "sources", "done");
        assertThat(second.metaReason).isEqualTo(FallbackReason.LLM_UNAVAILABLE);
    }

    @Test
    void JSON_경로와_스트림_경로가_같은_일일_예산을_쓴다() {
        var llm = new StubLlm();
        llm.chunks = List.of("답이에요.");
        var service = service(new LlmClient() {
            @Override
            public LlmAnswer answer(String question, List<KnowledgeSection> sections) {
                return new LlmAnswer("JSON 답이에요.", List.of());
            }

            @Override
            public LlmAnswer stream(String question, List<KnowledgeSection> sections, Consumer<String> onDelta) {
                llm.streamCalls.incrementAndGet();
                return answer(question, sections);
            }
        }, 1, 4);
        service.ask(1L, "오픈 시각이 언제예요?");
        var sink = new RecordingSink();

        run(service.prepareStream(1L, "오픈 시각이 언제예요?"), sink);

        assertThat(llm.streamCalls).hasValue(0);
        assertThat(sink.metaMode).isEqualTo(Mode.SEARCH);
    }

    @Test
    void 동시_호출_상한은_스트림이_끝날_때까지_쥐고_넘으면_검색_모드로_보낸다() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        LlmClient slow = new LlmClient() {
            @Override
            public LlmAnswer answer(String question, List<KnowledgeSection> sections) {
                throw new AssertionError();
            }

            @Override
            public LlmAnswer stream(String question, List<KnowledgeSection> sections, Consumer<String> onDelta) {
                onDelta.accept("느린 ");
                entered.countDown();
                await(release);
                onDelta.accept("답이에요.");
                return new LlmAnswer("느린 답이에요.", List.of());
            }
        };
        var service = service(slow, 10, 1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = new RecordingSink();
            var firstDone = executor.submit(() -> run(service.prepareStream(1L, "오픈 시각이 언제예요?"), first));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            var second = new RecordingSink();
            run(service.prepareStream(2L, "오픈 시각이 언제예요?"), second);

            assertThat(second.names).containsExactly("meta", "sources", "done");
            assertThat(second.metaReason).isEqualTo(FallbackReason.LLM_UNAVAILABLE);

            release.countDown();
            firstDone.get(5, TimeUnit.SECONDS);
            assertThat(first.doneMode).isEqualTo(Mode.AI);
            // 끝나면 자리가 돌아온다
            var third = new RecordingSink();
            run(service.prepareStream(3L, "오픈 시각이 언제예요?"), third);
            assertThat(third.metaMode).isEqualTo(Mode.AI);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void 요청_제한은_이벤트를_흘리기_전에_거절하고_JSON_경로와_횟수를_함께_센다() {
        var llm = new StubLlm();
        llm.chunks = List.of("답이에요.");
        var service = new AssistantService(retriever, new UserRateLimiter(clock, 1, 10),
                new DailyBudget(clock, 10), llm, 4);
        service.ask(1L, "오픈 시각이 언제예요?");

        assertThatThrownBy(() -> service.prepareStream(1L, "오픈 시각이 언제예요?"))
                .isInstanceOf(RateLimitedException.class);
        assertThat(llm.streamCalls).hasValue(0);
    }

    @Test
    void 클라이언트가_끊기면_Claude_스트림을_멈추고_자리를_돌려준다() {
        var llm = new StubLlm();
        llm.chunks = List.of("하나", "둘", "셋");
        var service = service(llm, 10, 1);
        var gone = new RecordingSink();
        gone.disconnectAtEvent = 3; // meta, delta까지 나가고 두 번째 delta에서 끊김

        run(service.prepareStream(1L, "오픈 시각이 언제예요?"), gone);

        assertThat(gone.names).containsExactly("meta", "delta");
        assertThat(gone.doneAnswer).isNull();
        // 자리가 돌아와 다음 호출이 다시 LLM으로 간다
        var next = new RecordingSink();
        run(service.prepareStream(2L, "오픈 시각이 언제예요?"), next);
        assertThat(next.metaMode).isEqualTo(Mode.AI);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
