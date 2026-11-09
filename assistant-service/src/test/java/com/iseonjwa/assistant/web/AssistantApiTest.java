package com.iseonjwa.assistant.web;

import com.iseonjwa.assistant.answer.Answer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 키 없이(검색 모드) 뜨는 앱의 HTTP 계약을 확인한다. 응답 JSON은 frontend/src/api/assistant.ts의 zod 스키마와 같아야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssistantApiTest {
    private static final AtomicLong NEXT_USER = new AtomicLong(1000);

    @Autowired
    MockMvc mvc;

    private static MockHttpServletRequestBuilder ask(long userId, String body) {
        return post("/api/v1/assistant/messages").header("X-User-Id", userId).header("X-User-Role", "USER")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static long newUser() {
        return NEXT_USER.incrementAndGet();
    }

    @Test
    void 키가_없으면_검색_모드로_답하고_응답이_프론트_계약과_같다() throws Exception {
        var result = mvc.perform(ask(newUser(), "{\"question\":\"환불은 언제 되나요?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.answer").value("관련 안내를 찾았어요. 아래 내용을 확인해 주세요."))
                .andExpect(jsonPath("$.data.mode").value("SEARCH"))
                .andExpect(jsonPath("$.data.fallbackReason").value("LLM_DISABLED"))
                .andExpect(jsonPath("$.data.sources.length()").value(3))
                .andExpect(jsonPath("$.data.sources[0].id").isString())
                .andExpect(jsonPath("$.data.sources[0].title").isString())
                .andExpect(jsonPath("$.data.sources[0].excerpt").isString())
                .andReturn();

        // zod 객체는 정해진 키만 읽지만, 계약에 없는 키가 새는지는 여기서 막는다
        var body = new tools.jackson.databind.json.JsonMapper().readTree(result.getResponse().getContentAsString());
        assertThat(body.get("data").propertyNames()).containsExactlyInAnyOrder("answer", "mode", "fallbackReason", "sources");
        assertThat(body.get("data").get("sources").get(0).propertyNames())
                .containsExactlyInAnyOrder("id", "title", "excerpt");
        assertThat(Set.of(Answer.Mode.values())).extracting(Enum::name).containsExactlyInAnyOrder("AI", "SEARCH");
    }

    @Test
    void 질문이_비었거나_공백이면_400_VALIDATION_FAILED와_question_오류를_준다() throws Exception {
        for (var body : new String[] {"{\"question\":\"\"}", "{\"question\":\"   \"}", "{}"}) {
            mvc.perform(ask(newUser(), body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("question"));
        }
    }

    @Test
    void 질문이_오백_자를_넘으면_400이고_정확히_오백_자는_받는다() throws Exception {
        mvc.perform(ask(newUser(), "{\"question\":\"" + "가".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("question"));

        mvc.perform(ask(newUser(), "{\"question\":\"" + "가".repeat(500) + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void 깨진_JSON은_400이다() throws Exception {
        mvc.perform(ask(newUser(), "{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 사용자_헤더가_없으면_401이다() throws Exception {
        mvc.perform(post("/api/v1/assistant/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"환불\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 분당_다섯_번을_넘으면_429와_Retry_After를_준다() throws Exception {
        var user = newUser();
        for (var i = 0; i < 5; i++) {
            mvc.perform(ask(user, "{\"question\":\"오픈 시각이 언제예요?\"}")).andExpect(status().isOk());
        }

        mvc.perform(ask(user, "{\"question\":\"오픈 시각이 언제예요?\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        // 다른 사용자는 영향이 없다
        mvc.perform(ask(newUser(), "{\"question\":\"오픈 시각이 언제예요?\"}")).andExpect(status().isOk());
    }
}
