package com.iseonjwa.assistant.web;

import com.iseonjwa.assistant.knowledge.KnowledgeSection;
import com.iseonjwa.assistant.llm.LlmClient;
import com.iseonjwa.assistant.llm.LlmUnavailableException;
import com.iseonjwa.assistant.support.Sections;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 스트리밍 엔드포인트의 HTTP 계약(헤더·이벤트 모양·SSE 전의 JSON 오류)을 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AssistantStreamApiTest.StubLlmConfiguration.class)
class AssistantStreamApiTest {
    private static final AtomicLong NEXT_USER = new AtomicLong(5000);
    private static final JsonMapper JSON = new JsonMapper();

    @TestConfiguration
    static class StubLlmConfiguration {
        @Bean
        LlmClient stubLlm() {
            return new LlmClient() {
                @Override
                public LlmAnswer answer(String question, List<KnowledgeSection> sections) {
                    return new LlmAnswer("JSON 답이에요.", List.of());
                }

                @Override
                public LlmAnswer stream(String question, List<KnowledgeSection> sections, Consumer<String> onDelta) {
                    if (question.contains("실패")) {
                        onDelta.accept("답하던 ");
                        throw new LlmUnavailableException("끊김");
                    }
                    onDelta.accept("자동으로 ");
                    onDelta.accept("환불돼요.");
                    return new LlmAnswer("자동으로 환불돼요.", List.of(Sections.byId("payment.auto-refund")));
                }
            };
        }
    }

    @Autowired
    MockMvc mvc;

    private static MockHttpServletRequestBuilder stream(long userId, String body) {
        return post("/api/v1/assistant/messages/stream").header("X-User-Id", userId).header("X-User-Role", "USER")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static long newUser() {
        return NEXT_USER.incrementAndGet();
    }

    /** SSE 본문을 (이벤트 이름, data JSON) 목록으로 읽는다. */
    private record Event(String name, JsonNode data) {
    }

    private static List<Event> events(MvcResult result) throws Exception {
        result.getAsyncResult(5000);
        var events = new ArrayList<Event>();
        for (var block : result.getResponse().getContentAsString(StandardCharsets.UTF_8).split("\n\n")) {
            if (block.isBlank()) {
                continue;
            }
            var lines = block.split("\n");
            assertThat(lines).as("이벤트는 event 줄과 data 한 줄이다: %s", block).hasSize(2);
            assertThat(lines[0]).startsWith("event:");
            assertThat(lines[1]).startsWith("data:");
            events.add(new Event(lines[0].substring("event:".length()).strip(),
                    JSON.readTree(lines[1].substring("data:".length()).strip())));
        }
        return events;
    }

    @Test
    void 정상이면_SSE_헤더와_계약_순서의_이벤트를_준다() throws Exception {
        var result = mvc.perform(stream(newUser(), "{\"question\":\"환불은 언제 되나요?\"}"))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/event-stream;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andReturn();

        var events = events(result);

        assertThat(events).extracting(Event::name).containsExactly("meta", "delta", "delta", "sources", "done");
        assertThat(events.get(0).data().propertyNames()).containsExactlyInAnyOrder("mode", "fallbackReason");
        assertThat(events.get(0).data().get("mode").asString()).isEqualTo("AI");
        assertThat(events.get(0).data().get("fallbackReason").isNull()).isTrue();
        var joined = events.get(1).data().get("text").asString() + events.get(2).data().get("text").asString();
        assertThat(events.get(3).data().get("sources")).hasSize(1);
        assertThat(events.get(3).data().get("sources").get(0).propertyNames())
                .containsExactlyInAnyOrder("id", "title", "excerpt");
        var done = events.get(4).data();
        assertThat(done.propertyNames()).containsExactlyInAnyOrder("answer", "mode", "fallbackReason");
        assertThat(done.get("answer").asString()).isEqualTo(joined).isEqualTo("자동으로 환불돼요.");
        assertThat(done.get("mode").asString()).isEqualTo("AI");
    }

    @Test
    void 조각_뒤_실패하면_fallback으로_바뀌고_done은_SEARCH_고정_문장이다() throws Exception {
        var result = mvc.perform(stream(newUser(), "{\"question\":\"환불 실패 질문\"}"))
                .andExpect(request().asyncStarted()).andReturn();

        var events = events(result);

        assertThat(events).extracting(Event::name).containsExactly("meta", "delta", "fallback", "sources", "done");
        assertThat(events.get(2).data().get("fallbackReason").asString()).isEqualTo("LLM_UNAVAILABLE");
        assertThat(events.get(3).data().get("sources")).hasSize(3);
        assertThat(events.get(4).data().get("mode").asString()).isEqualTo("SEARCH");
        assertThat(events.get(4).data().get("answer").asString()).isEqualTo("관련 안내를 찾았어요. 아래 내용을 확인해 주세요.");
    }

    @Test
    void 질문이_잘못되면_SSE가_아니라_400_JSON이다() throws Exception {
        mvc.perform(stream(newUser(), "{\"question\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/json")))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("question"));
    }

    @Test
    void 사용자_헤더가_없으면_401_JSON이다() throws Exception {
        mvc.perform(post("/api/v1/assistant/messages/stream").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"환불\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/json")))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 분당_다섯_번을_넘으면_SSE를_열지_않고_429_JSON과_Retry_After를_준다() throws Exception {
        var user = newUser();
        for (var i = 0; i < 5; i++) {
            var result = mvc.perform(stream(user, "{\"question\":\"오픈 시각이 언제예요?\"}"))
                    .andExpect(request().asyncStarted()).andReturn();
            events(result);
        }

        mvc.perform(stream(user, "{\"question\":\"오픈 시각이 언제예요?\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/json")))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }
}
