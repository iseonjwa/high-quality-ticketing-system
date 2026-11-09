package com.iseonjwa.assistant.llm;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.iseonjwa.assistant.support.Sections;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 가짜 Anthropic 서버가 Messages 스트리밍 형식(text/event-stream)으로 답하게 해, 글자 조각 전달·인용 매핑·시한을 확인한다.
 */
class AnthropicLlmClientStreamTest {
    private static final JsonMapper JSON = new JsonMapper();

    /** 서버가 보낼 이벤트 한 덩어리. 보내기 전에 {@code pauseMillis}만큼 쉰다. */
    private record Frame(String event, String data, long pauseMillis) {
    }

    private HttpServer server;
    private final CopyOnWriteArrayList<JsonNode> requestBodies = new CopyOnWriteArrayList<>();
    private volatile List<Frame> frames = List.of();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestBodies.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
        exchange.getResponseHeaders().add("content-type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (var out = exchange.getResponseBody()) {
            for (var frame : frames) {
                if (frame.pauseMillis() > 0) {
                    Thread.sleep(frame.pauseMillis());
                }
                out.write(("event: " + frame.event() + "\ndata: " + frame.data() + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException | InterruptedException exception) {
            // 클라이언트가 먼저 닫은 경우(시한 초과 테스트)
        }
    }

    private AnthropicLlmClient client(Duration firstDelta, Duration total) {
        var sdk = AnthropicOkHttpClient.builder()
                .apiKey(UUID.randomUUID().toString())
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                // 운영과 같이 전역 시한은 짧게 둔다. 스트림 호출은 이 값에 걸리지 않아야 한다
                .timeout(Duration.ofMillis(500))
                .maxRetries(0)
                .build();
        return new AnthropicLlmClient(sdk, "claude-haiku-5-5", 1024, firstDelta, total);
    }

    private static Frame frame(String event, String data) {
        return new Frame(event, data, 0);
    }

    private static Frame start() {
        return frame("message_start", """
                {"type":"message_start","message":{"id":"msg_test","type":"message","role":"assistant",
                 "model":"claude-haiku-5-5","content":[],"stop_reason":null,"stop_sequence":null,
                 "usage":{"input_tokens":120,"output_tokens":1}}}""".replace("\n", ""));
    }

    private static Frame blockStart(int index) {
        return frame("content_block_start",
                "{\"type\":\"content_block_start\",\"index\":" + index + ",\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
    }

    private static Frame delta(int index, String text, long pauseMillis) {
        return new Frame("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":" + index
                + ",\"delta\":{\"type\":\"text_delta\",\"text\":\"" + text + "\"}}", pauseMillis);
    }

    private static Frame citation(int index, int documentIndex) {
        return frame("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":" + index
                + ",\"delta\":{\"type\":\"citations_delta\",\"citation\":{\"type\":\"char_location\","
                + "\"cited_text\":\"자동으로 환불돼요\",\"document_index\":" + documentIndex
                + ",\"document_title\":\"x\",\"start_char_index\":0,\"end_char_index\":9}}}");
    }

    private static Frame blockStop(int index) {
        return frame("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":" + index + "}");
    }

    private static List<Frame> finish(String stopReason) {
        return List.of(
                frame("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + stopReason
                        + "\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":30}}"),
                frame("message_stop", "{\"type\":\"message_stop\"}"));
    }

    private static List<Frame> frames(Frame... head) {
        return new ArrayList<>(List.of(head));
    }

    @Test
    void 글자_조각을_받는_대로_넘기고_인용을_절로_바꾼다() {
        var sequence = frames(start(), frame("ping", "{\"type\":\"ping\"}"), blockStart(0),
                delta(0, "시간이 지난 뒤 ", 0), delta(0, "승인되면 ", 0), citation(0, 1),
                delta(0, "자동으로 환불돼요.", 0), blockStop(0));
        sequence.addAll(finish("end_turn"));
        frames = sequence;
        var received = new ArrayList<String>();

        var answer = client(Duration.ofSeconds(5), Duration.ofSeconds(10)).stream("환불은 언제 되나요?",
                List.of(Sections.byId("queue.why"), Sections.byId("payment.auto-refund")), received::add);

        assertThat(received).containsExactly("시간이 지난 뒤 ", "승인되면 ", "자동으로 환불돼요.");
        assertThat(answer.text()).isEqualTo("시간이 지난 뒤 승인되면 자동으로 환불돼요.");
        assertThat(answer.citedSections()).extracting(section -> section.id()).containsExactly("payment.auto-refund");
    }

    @Test
    void 요청_본문은_JSON_호출과_같고_stream만_켠다() {
        var sequence = frames(start(), blockStart(0), delta(0, "답이에요.", 0), blockStop(0));
        sequence.addAll(finish("end_turn"));
        frames = sequence;

        client(Duration.ofSeconds(5), Duration.ofSeconds(10)).stream("질문이에요", List.of(Sections.byId("queue.why")), text -> {
        });

        var body = requestBodies.get(0);
        assertThat(body.get("stream").asBoolean()).isTrue();
        assertThat(body.get("model").asString()).isEqualTo("claude-haiku-5-5");
        assertThat(body.get("output_config").get("effort").asString()).isEqualTo("low");
        var content = body.get("messages").get(0).get("content");
        assertThat(content.get(0).get("type").asString()).isEqualTo("document");
        assertThat(content.get(0).get("citations").get("enabled").asBoolean()).isTrue();
        assertThat(content.get(1).get("text").asString()).isEqualTo("<question>질문이에요</question>");
    }

    @Test
    void 전역_시한보다_오래_걸리는_스트림도_끝까지_받는다() {
        var sequence = frames(start(), blockStart(0), delta(0, "하나 ", 300), delta(0, "둘 ", 300),
                delta(0, "셋.", 300), blockStop(0));
        sequence.addAll(finish("end_turn"));
        frames = sequence;
        var received = new ArrayList<String>();

        // 전체 약 0.9초라 전역 시한(0.5초)을 넘지만 스트림 호출에는 걸리지 않는다
        var answer = client(Duration.ofSeconds(2), Duration.ofSeconds(5)).stream("질문이에요",
                List.of(Sections.byId("queue.why")), received::add);

        assertThat(received).hasSize(3);
        assertThat(answer.text()).isEqualTo("하나 둘 셋.");
    }

    @Test
    void stop_reason이_refusal이면_조각이_나간_뒤라도_사용_불가로_본다() {
        var sequence = frames(start(), blockStart(0), delta(0, "답할 수", 0), blockStop(0));
        sequence.addAll(finish("refusal"));
        frames = sequence;
        var received = new ArrayList<String>();

        assertThatThrownBy(() -> client(Duration.ofSeconds(5), Duration.ofSeconds(10)).stream("질문이에요",
                List.of(Sections.byId("queue.why")), received::add))
                .isInstanceOf(LlmUnavailableException.class).hasMessageContaining("거절");
        assertThat(received).containsExactly("답할 수");
    }

    @Test
    void 첫_조각이_시한까지_없으면_스트림을_닫고_사용_불가로_본다() {
        var sequence = frames(start(), blockStart(0), delta(0, "늦은 답이에요.", 3000), blockStop(0));
        sequence.addAll(finish("end_turn"));
        frames = sequence;
        var received = new ArrayList<String>();
        var startedAt = System.nanoTime();

        assertThatThrownBy(() -> client(Duration.ofMillis(400), Duration.ofSeconds(10)).stream("질문이에요",
                List.of(Sections.byId("queue.why")), received::add))
                .isInstanceOf(LlmUnavailableException.class);

        assertThat(received).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void 첫_조각은_왔어도_전체_시한을_넘으면_스트림을_닫는다() {
        var sequence = frames(start(), blockStart(0));
        for (var i = 0; i < 20; i++) {
            sequence.add(delta(0, "가", 200));
        }
        frames = sequence;
        var received = new ArrayList<String>();
        var startedAt = System.nanoTime();

        assertThatThrownBy(() -> client(Duration.ofSeconds(2), Duration.ofMillis(900)).stream("질문이에요",
                List.of(Sections.byId("queue.why")), received::add))
                .isInstanceOf(LlmUnavailableException.class);

        assertThat(received).isNotEmpty().hasSizeLessThan(20);
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void message_stop_없이_스트림이_끊기면_사용_불가로_본다() {
        frames = frames(start(), blockStart(0), delta(0, "반쪽 답", 0));

        assertThatThrownBy(() -> client(Duration.ofSeconds(5), Duration.ofSeconds(10)).stream("질문이에요",
                List.of(Sections.byId("queue.why")), text -> {
                }))
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void 조각_처리_중_던진_예외는_감싸지_않고_그대로_올리고_스트림을_닫는다() {
        var sequence = frames(start(), blockStart(0), delta(0, "하나", 0), delta(0, "둘", 0), blockStop(0));
        sequence.addAll(finish("end_turn"));
        frames = sequence;
        var received = new ArrayList<String>();

        assertThatThrownBy(() -> client(Duration.ofSeconds(5), Duration.ofSeconds(10)).stream("질문이에요",
                List.of(Sections.byId("queue.why")), text -> {
                    received.add(text);
                    throw new IllegalStateException("클라이언트 끊김");
                }))
                .isInstanceOf(IllegalStateException.class).hasMessage("클라이언트 끊김");
        assertThat(received).containsExactly("하나");
    }

    @Test
    void HTTP_오류_응답은_사용_불가로_본다() throws IOException {
        server.removeContext("/v1/messages");
        server.createContext("/v1/messages", exchange -> {
            exchange.getRequestBody().readAllBytes();
            var body = "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(429, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        assertThatThrownBy(() -> client(Duration.ofSeconds(5), Duration.ofSeconds(10)).stream("질문이에요",
                List.of(Sections.byId("queue.why")), text -> {
                }))
                .isInstanceOf(LlmUnavailableException.class);
    }
}
