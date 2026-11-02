package com.iseonjwa.mockpg.payment;

import com.iseonjwa.mockpg.fault.FaultInjector;
import com.iseonjwa.mockpg.fault.FaultMode;
import com.iseonjwa.mockpg.fault.FaultRules;
import com.iseonjwa.mockpg.fault.FaultTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 운영형 장애 모드(mock-pg.md 5절 모드와 적용 표 중 승인·조회·망취소)를 HTTP로 확인한다. 응답 유실의 승인은
 * {@link ApprovalDropResponseTest}가 맡는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FaultModesApiTest {
    private static final long DELAY_MILLIS = 300;
    private static final int SOCKET_READ_TIMEOUT_MILLIS = 5_000;
    private static final String PROCESSING = "{\"status\":\"PROCESSING\"}";
    private static final String NOT_FOUND = "{\"code\":\"NOT_FOUND\"}";
    private static final String SERVER_ERROR = "{\"code\":\"SERVER_ERROR\"}";
    private static final String ORDER_CANCELED = "{\"code\":\"ORDER_CANCELED\"}";
    private static final String DECLINED = "{\"status\":\"DECLINED\",\"declineCode\":\"CARD_DECLINED\"}";

    @LocalServerPort
    int port;

    @Autowired
    FaultInjector faults;

    private final HttpClient http = HttpClient.newHttpClient();
    private final String orderId = UUID.randomUUID().toString();

    @AfterEach
    void clearFaults() {
        for (var target : FaultTarget.values()) {
            faults.apply(FaultRules.off(target));
        }
    }

    @Nested
    class 승인 {
        @Test
        void 지연을_걸면_지연_동안_조회는_처리_중이고_끝나면_승인이다() throws Exception {
            faults.apply(FaultRules.delay(FaultTarget.APPROVE, 500, 1));
            var startedAt = System.nanoTime();

            var approval = CompletableFuture.supplyAsync(() -> unchecked(() -> approve(orderId)));
            awaitStatus(202, orderId);
            var approved = approval.get(5, TimeUnit.SECONDS);

            assertEquals(200, approved.statusCode(), approved.body());
            assertTrue(Duration.ofNanos(System.nanoTime() - startedAt).toMillis() >= 500);
            assertTrue(find(orderId).body().contains("\"status\":\"APPROVED\""));
        }

        @Test
        void 이미_결과가_있는_주문에_지연을_걸면_응답_직전에_지연하고_저장된_결과를_돌려준다() throws Exception {
            var first = approve(orderId);
            faults.apply(FaultRules.delay(FaultTarget.APPROVE, DELAY_MILLIS, 1));
            var startedAt = System.nanoTime();

            var again = approve(orderId);

            assertTrue(Duration.ofNanos(System.nanoTime() - startedAt).toMillis() >= DELAY_MILLIS);
            assertResponse(200, first.body(), again);
        }

        @Test
        void 거절을_걸면_새_주문을_거절로_저장하고_장애를_풀어도_거절이다() throws Exception {
            faults.apply(FaultRules.of(FaultTarget.APPROVE, FaultMode.DECLINE, 1));

            assertResponse(200, DECLINED, approve(orderId));
            assertResponse(200, DECLINED, find(orderId));
            assertResponse(200, DECLINED, approve(orderId));
        }

        @Test
        void 거절은_이미_승인된_주문의_결과를_바꾸지_못한다() throws Exception {
            var first = approve(orderId);
            faults.apply(FaultRules.of(FaultTarget.APPROVE, FaultMode.DECLINE, 1));

            assertResponse(200, first.body(), approve(orderId));
        }

        @Test
        void 처리_중을_걸면_202이고_원장은_그대로라_조회는_404다() throws Exception {
            faults.apply(FaultRules.of(FaultTarget.APPROVE, FaultMode.PROCESSING, 1));

            assertResponse(202, PROCESSING, approve(orderId));
            assertResponse(404, NOT_FOUND, find(orderId));
        }

        @Test
        void 서버_오류를_걸면_500이고_원장은_그대로라_조회는_404다() throws Exception {
            faults.apply(FaultRules.of(FaultTarget.APPROVE, FaultMode.SERVER_ERROR, 1));

            assertResponse(500, SERVER_ERROR, approve(orderId));
            assertResponse(404, NOT_FOUND, find(orderId));
        }
    }

    @Nested
    class 조회 {
        @Test
        void 지연을_걸면_지연한_뒤_조회한다() throws Exception {
            var approved = approve(orderId);
            faults.apply(FaultRules.delay(FaultTarget.PAYMENT_LOOKUP, DELAY_MILLIS, 1));
            var startedAt = System.nanoTime();

            var found = find(orderId);

            assertTrue(Duration.ofNanos(System.nanoTime() - startedAt).toMillis() >= DELAY_MILLIS);
            assertResponse(200, approved.body(), found);
        }

        @Test
        void 처리_중을_걸면_승인된_주문도_202다() throws Exception {
            approve(orderId);
            faults.apply(FaultRules.of(FaultTarget.PAYMENT_LOOKUP, FaultMode.PROCESSING, 1));

            assertResponse(202, PROCESSING, find(orderId));
            assertEquals(200, find(orderId).statusCode());
        }

        @Test
        void 서버_오류를_걸면_승인된_주문도_500이다() throws Exception {
            approve(orderId);
            faults.apply(FaultRules.of(FaultTarget.PAYMENT_LOOKUP, FaultMode.SERVER_ERROR, 1));

            assertResponse(500, SERVER_ERROR, find(orderId));
            assertEquals(200, find(orderId).statusCode());
        }
    }

    @Nested
    class 망취소 {
        @Test
        void 지연을_걸면_지연한_뒤_망취소를_반영한다() throws Exception {
            faults.apply(FaultRules.delay(FaultTarget.CANCEL, DELAY_MILLIS, 1));
            var startedAt = System.nanoTime();

            var canceled = cancel(orderId);

            assertTrue(Duration.ofNanos(System.nanoTime() - startedAt).toMillis() >= DELAY_MILLIS);
            assertResponse(200, "{\"status\":\"CANCELED\"}", canceled);
            assertResponse(409, ORDER_CANCELED, approve(orderId));
        }

        @Test
        void 응답_유실을_걸면_한_바이트도_보내지_않지만_망취소는_저장된다() throws Exception {
            faults.apply(FaultRules.of(FaultTarget.CANCEL, FaultMode.DROP_RESPONSE, 1));

            assertEquals(-1, sendRawCancel(orderId), "응답 바이트를 받았다");
            assertResponse(409, ORDER_CANCELED, approve(orderId));
        }

        @Test
        void 처리_중을_걸면_202이고_망취소하지_않아_이후_승인은_200이다() throws Exception {
            faults.apply(FaultRules.of(FaultTarget.CANCEL, FaultMode.PROCESSING, 1));

            assertResponse(202, PROCESSING, cancel(orderId));
            assertEquals(200, approve(orderId).statusCode());
        }

        @Test
        void 서버_오류를_걸면_500이고_망취소하지_않아_이후_승인은_200이다() throws Exception {
            faults.apply(FaultRules.of(FaultTarget.CANCEL, FaultMode.SERVER_ERROR, 1));

            assertResponse(500, SERVER_ERROR, cancel(orderId));
            assertEquals(200, approve(orderId).statusCode());
        }
    }

    @Nested
    class 설정 {
        @Test
        void 다른_주문의_요청은_규칙을_적용받지_않고_횟수도_남는다() throws Exception {
            var other = UUID.randomUUID().toString();
            faults.apply(FaultRules.forOrder(FaultTarget.APPROVE, FaultMode.PROCESSING, 1, orderId));

            assertEquals(200, approve(other).statusCode());
            assertEquals(1, faults.currentRules().stream()
                    .filter(rule -> rule.target() == FaultTarget.APPROVE).findFirst().orElseThrow().count());
            assertResponse(202, PROCESSING, approve(orderId));
            assertEquals(200, approve(orderId).statusCode());
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "{\"target\":\"PAYMENT_LOOKUP\",\"mode\":\"DROP_RESPONSE\"}",
                "{\"target\":\"PAYMENT_LOOKUP\",\"mode\":\"DECLINE\"}",
                "{\"target\":\"CANCEL\",\"mode\":\"DECLINE\"}",
                "{\"target\":\"APPROVE\",\"mode\":\"DELAY\"}",
                "{\"target\":\"APPROVE\",\"mode\":\"DELAY\",\"delayMs\":60001}",
                "{\"target\":\"APPROVE\",\"mode\":\"PROCESSING\",\"rate\":1.5}",
                "{\"target\":\"APPROVE\",\"mode\":\"PROCESSING\",\"count\":0}",
                "{\"target\":\"APPROVE\",\"mode\":\"PROCESSING\",\"orderId\":\" \"}",
                "{\"target\":\"REFUND\",\"mode\":\"PROCESSING\"}"
        })
        void 설정이_틀리면_400_INVALID_REQUEST다(String body) throws Exception {
            assertResponse(400, "{\"code\":\"INVALID_REQUEST\"}", post("/mock-pg/faults", body));
        }

        @Test
        void 설정한_규칙은_조회에_그대로_보인다() throws Exception {
            var body = "{\"target\":\"CANCEL\",\"mode\":\"DELAY\",\"delayMs\":300,\"count\":2,\"orderId\":\"o-1\"}";

            assertEquals(200, post("/mock-pg/faults", body).statusCode());

            var current = send(HttpRequest.newBuilder(uri("/mock-pg/faults")).GET().build());
            assertTrue(current.body().contains(
                    "{\"target\":\"CANCEL\",\"mode\":\"DELAY\",\"delayMs\":300,\"rate\":1.0,\"count\":2,\"orderId\":\"o-1\"}"),
                    current.body());
        }
    }

    // 지연 중인 승인이 원장에 IN_FLIGHT를 남길 때까지 조회해서, 조회가 지연 중에 도착하게 한다
    private void awaitStatus(int expected, String orderId) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (find(orderId).statusCode() == expected) return;
            Thread.sleep(10);
        }
        throw new AssertionError("조회가 " + expected + "을 받지 못했다");
    }

    // 망취소 요청을 원시 소켓으로 보내고 응답의 첫 바이트를 돌려준다. 연결이 응답 없이 닫혔으면 -1이다
    private int sendRawCancel(String orderId) throws IOException {
        var head = ("POST /mock-pg/payments/" + orderId + "/cancel HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + port + "\r\n"
                + "Content-Length: 0\r\n"
                + "\r\n").getBytes(StandardCharsets.US_ASCII);
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(SOCKET_READ_TIMEOUT_MILLIS);
            var out = socket.getOutputStream();
            out.write(head);
            out.flush();
            return socket.getInputStream().read();
        }
    }

    private HttpResponse<String> approve(String orderId) throws Exception {
        return post("/mock-pg/payments", "{\"orderId\":\"" + orderId + "\",\"amount\":50000,\"currency\":\"KRW\"}");
    }

    private HttpResponse<String> cancel(String orderId) throws Exception {
        return send(HttpRequest.newBuilder(uri("/mock-pg/payments/" + orderId + "/cancel"))
                .POST(HttpRequest.BodyPublishers.noBody()).build());
    }

    private HttpResponse<String> find(String orderId) throws Exception {
        return send(HttpRequest.newBuilder(uri("/mock-pg/payments/" + orderId)).GET().build());
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static void assertResponse(int status, String body, HttpResponse<String> response) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(body, response.body());
    }

    private static <T> T unchecked(ThrowingSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
