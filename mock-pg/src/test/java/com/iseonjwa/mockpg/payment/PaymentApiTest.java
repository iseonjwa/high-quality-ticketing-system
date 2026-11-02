package com.iseonjwa.mockpg.payment;

import com.iseonjwa.mockpg.ledger.LedgerHolder;
import com.iseonjwa.mockpg.ledger.OrderState.Approved;
import com.iseonjwa.mockpg.ledger.OrderState.Declined;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mock-pg.md 4절 상태 표의 응답을 HTTP로 확인한다. 거절·처리 중 상태는 장애 주입과 무관하게 상태 표의 칸을 결정적으로 만들려고 원장에 직접 만든다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PaymentApiTest {
    private static final String CANCELED = "{\"status\":\"CANCELED\"}";
    private static final String PROCESSING = "{\"status\":\"PROCESSING\"}";
    private static final String NOT_FOUND = "{\"code\":\"NOT_FOUND\"}";

    @LocalServerPort
    int port;

    @Autowired
    LedgerHolder ledgers;

    private final HttpClient http = HttpClient.newHttpClient();
    private final String orderId = UUID.randomUUID().toString();

    @Nested
    class 처음_보는_주문 {
        @Test
        void 승인하면_PG_거래_식별자와_금액_통화_승인_시각을_돌려준다() throws Exception {
            var approved = approve(orderId);

            assertEquals(200, approved.statusCode());
            assertTrue(approved.body().contains("\"status\":\"APPROVED\""), approved.body());
            assertTrue(approved.body().contains("\"amount\":50000"), approved.body());
            assertTrue(approved.body().contains("\"currency\":\"KRW\""), approved.body());
            // 어댑터는 승인 시각을 ISO-8601 문자열로 읽는다(Instant.parse)
            assertTrue(approved.body().matches(".*\"approvedAt\":\"\\d{4}-\\d{2}-\\d{2}T[^\"]+Z\".*"), approved.body());
        }

        @Test
        void 망취소하면_CANCELED다() throws Exception {
            assertResponse(200, CANCELED, cancel(orderId));
        }

        @Test
        void 조회하면_본문_코드가_있는_404다() throws Exception {
            assertResponse(404, NOT_FOUND, find(orderId));
        }
    }

    @Nested
    class 승인된_주문 {
        @Test
        void 다시_승인하면_처음_저장한_승인을_돌려준다() throws Exception {
            var first = approve(orderId);

            assertResponse(200, first.body(), approve(orderId));
        }

        @Test
        void 망취소하면_승인_사실을_승인_응답으로_알린다() throws Exception {
            var approved = approve(orderId);

            assertResponse(200, approved.body(), cancel(orderId));
            assertResponse(200, approved.body(), find(orderId));
        }
    }

    @Nested
    class 거절된_주문 {
        private static final String DECLINED = "{\"status\":\"DECLINED\",\"declineCode\":\"CARD_DECLINED\"}";

        @Test
        void 다시_승인하거나_조회하면_저장한_거절을_돌려준다() throws Exception {
            ledgers.current().approve(orderId, () -> new Declined("CARD_DECLINED"));

            assertResponse(200, DECLINED, approve(orderId));
            assertResponse(200, DECLINED, find(orderId));
        }

        @Test
        void 망취소하면_CANCELED만_답하고_거절_상태는_그대로다() throws Exception {
            ledgers.current().approve(orderId, () -> new Declined("CARD_DECLINED"));

            assertResponse(200, CANCELED, cancel(orderId));
            assertResponse(200, DECLINED, find(orderId));
        }
    }

    @Nested
    class 망취소된_주문 {
        @Test
        void 승인하면_409_ORDER_CANCELED이고_조회하면_404다() throws Exception {
            cancel(orderId);

            assertResponse(409, "{\"code\":\"ORDER_CANCELED\"}", approve(orderId));
            assertResponse(404, NOT_FOUND, find(orderId));
            assertResponse(200, CANCELED, cancel(orderId));
        }
    }

    @Nested
    class 처리_중인_주문 {
        @Test
        void 승인은_409_REQUEST_IN_PROGRESS이고_망취소와_조회는_202이며_처리는_승인으로_끝난다() throws Exception {
            var deciding = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var pending = executor.submit(() -> ledgers.current().approve(orderId, () -> {
                    deciding.countDown();
                    await(release);
                    return new Approved(orderId, "pg-" + UUID.randomUUID(), BigDecimal.valueOf(50000), "KRW",
                            Instant.now());
                }));
                assertTrue(deciding.await(5, TimeUnit.SECONDS));
                try {
                    assertResponse(409, "{\"code\":\"REQUEST_IN_PROGRESS\"}", approve(orderId));
                    assertResponse(202, PROCESSING, cancel(orderId));
                    assertResponse(202, PROCESSING, find(orderId));
                } finally {
                    release.countDown();
                }
                pending.get(5, TimeUnit.SECONDS);
            }

            // 처리 중에 온 망취소는 상태를 바꾸지 않았으므로 승인이 그대로 확정된다
            assertTrue(find(orderId).body().contains("\"status\":\"APPROVED\""));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"amount\":50000,\"currency\":\"KRW\"}",
            "{\"orderId\":\" \",\"amount\":50000,\"currency\":\"KRW\"}",
            "{\"orderId\":\"o-1\",\"amount\":0,\"currency\":\"KRW\"}",
            "{\"orderId\":\"o-1\",\"amount\":-1000,\"currency\":\"KRW\"}",
            "{\"orderId\":\"o-1\",\"amount\":50000}",
            "{\"orderId\":\"o-1\",\"amount\":\"오만\",\"currency\":\"KRW\"}",
            "{not json"
    })
    void 형식이_틀린_승인_요청은_400_INVALID_REQUEST다(String body) throws Exception {
        assertResponse(400, "{\"code\":\"INVALID_REQUEST\"}", post("/mock-pg/payments", body));
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

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
