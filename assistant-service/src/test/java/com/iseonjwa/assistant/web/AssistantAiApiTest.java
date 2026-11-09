package com.iseonjwa.assistant.web;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 키가 있는 앱을 가짜 Anthropic 서버에 붙여 끝까지 확인한다. Anthropic SDK(Jackson 2)와 Boot(Jackson 3)가 한 앱에서 함께
 * 뜨고, AI 응답이 프론트 계약 모양으로 나오는지 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssistantAiApiTest {
    private static final HttpServer FAKE_ANTHROPIC = startFakeAnthropic();

    @Autowired
    MockMvc mvc;

    private static HttpServer startFakeAnthropic() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/messages", exchange -> {
                exchange.getRequestBody().readAllBytes();
                var body = """
                        {"id":"msg_test","type":"message","role":"assistant","model":"claude-haiku-5-5",
                         "content":[{"type":"text","text":"좌석이 풀린 뒤라 자동으로 환불돼요.",
                           "citations":[{"type":"char_location","cited_text":"자동으로 환불돼요","document_index":0,
                                         "document_title":"x","start_char_index":0,"end_char_index":9}]}],
                         "stop_reason":"end_turn","stop_sequence":null,
                         "usage":{"input_tokens":100,"output_tokens":20}}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("content-type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @DynamicPropertySource
    static void anthropic(DynamicPropertyRegistry registry) {
        // Note: 키는 실행할 때마다 만든 무작위 값이다. 실제 키 모양의 문자열을 저장소에 두지 않는다
        registry.add("anthropic.api-key", () -> UUID.randomUUID().toString());
        registry.add("anthropic.base-url", () -> "http://127.0.0.1:" + FAKE_ANTHROPIC.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        FAKE_ANTHROPIC.stop(0);
    }

    @Test
    void 키가_있으면_AI_모드로_답하고_인용한_절을_출처로_준다() throws Exception {
        mvc.perform(post("/api/v1/assistant/messages").header("X-User-Id", 4242).header("X-User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"환불은 언제 되나요?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("AI"))
                .andExpect(jsonPath("$.data.fallbackReason").value((Object) null))
                .andExpect(jsonPath("$.data.answer").value("좌석이 풀린 뒤라 자동으로 환불돼요."))
                .andExpect(jsonPath("$.data.sources.length()").value(1))
                .andExpect(jsonPath("$.data.sources[0].id").isString())
                .andExpect(jsonPath("$.data.sources[0].title").isString())
                .andExpect(jsonPath("$.data.sources[0].excerpt").isString());
    }
}
