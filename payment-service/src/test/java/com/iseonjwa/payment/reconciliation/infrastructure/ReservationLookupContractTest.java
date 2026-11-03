package com.iseonjwa.payment.reconciliation.infrastructure;

import com.iseonjwa.payment.reconciliation.application.ReservationLookupException;
import com.iseonjwa.payment.reconciliation.domain.ReservationStatus;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 예매 서비스 PR #283(ISE-75)의 {@code ReconciliationApiTest}가 확인하는 실제 응답 모양을 문자열로 두고 어댑터가 읽는지 고정한다.
 * 가짜 서버가 아니라 고정 문자열이라, 가짜 서버와 어댑터가 같이 틀려도 이 테스트가 잡는다.
 */
class ReservationLookupContractTest {
    private static final Instant BASE = Instant.parse("2001-01-01T00:00:00Z");

    // SuccessResponse로 감싼 ID 목록 조회. totalAmount는 BigDecimal이라 110000.00으로 오고, 확정 전 예매는 paymentId가 null이다
    private static final String BY_IDS = """
            {"status":200,"message":"대사용 예매를 조회했습니다","data":[
              {"reservationId":11,"userId":42,"status":"CONFIRMED","totalAmount":110000.00,"paymentId":9001,
               "holdExpiresAt":"2001-01-01T00:30:00Z","updatedAt":"2001-01-01T00:00:00Z"},
              {"reservationId":12,"userId":42,"status":"HELD","totalAmount":110000,"paymentId":null,
               "holdExpiresAt":"2001-01-01T00:30:00Z","updatedAt":"2001-01-01T00:00:00Z"}
            ],"traceId":"abc"}""";

    // 한 쪽이 차서 숫자 nextCursor가 온 변경 목록
    private static final String CHANGES_FULL = """
            {"status":200,"message":"대사용 예매 변경을 조회했습니다","data":{"items":[
              {"reservationId":21,"userId":42,"status":"CANCELED","totalAmount":330000.00,"paymentId":9002,
               "holdExpiresAt":"2001-01-01T00:30:00Z","updatedAt":"2001-01-01T00:00:10Z"}
            ],"nextCursor":21},"traceId":null}""";

    // 마지막 쪽
    private static final String CHANGES_LAST = """
            {"status":200,"message":"대사용 예매 변경을 조회했습니다","data":{"items":[],"nextCursor":null},"traceId":null}""";

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> lastQuery = new AtomicReference<>();
    private ReservationLookupHttpClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                lastQuery.set(exchange.getRequestURI().getQuery());
                var bytes = body.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        client = ReservationLookupHttpClient.create("http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofSeconds(1), Duration.ofSeconds(1), null);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 감싼_응답의_data_배열과_소수부_0_금액을_읽는다() {
        body.set(BY_IDS);

        var found = client.findByIds(List.of(11L, 12L));

        assertEquals(2, found.size());
        assertEquals(11, found.get(0).reservationId());
        assertEquals(42, found.get(0).userId());
        assertEquals(ReservationStatus.CONFIRMED, found.get(0).status());
        assertEquals(110000, found.get(0).totalAmount());
        assertEquals(9001L, found.get(0).paymentId());
        assertEquals(BASE.plusSeconds(1800), found.get(0).holdExpiresAt());
        assertEquals(BASE, found.get(0).updatedAt());
        assertEquals(110000, found.get(1).totalAmount());
        assertNull(found.get(1).paymentId());
        assertEquals("ids=11,12", lastQuery.get());
    }

    @Test
    void 변경_목록의_숫자_nextCursor를_문자열로_받아_그대로_다음_cursor로_넘긴다() {
        body.set(CHANGES_FULL);
        var first = client.findChanges(BASE, BASE.plusSeconds(3600), null);

        assertEquals(1, first.items().size());
        assertEquals(ReservationStatus.CANCELED, first.items().get(0).status());
        assertEquals(330000, first.items().get(0).totalAmount());
        assertEquals("21", first.nextCursor());
        assertEquals("from=2001-01-01T00:00:00Z&to=2001-01-01T01:00:00Z", lastQuery.get());

        body.set(CHANGES_LAST);
        var last = client.findChanges(BASE, BASE.plusSeconds(3600), first.nextCursor());

        assertEquals(0, last.items().size());
        assertNull(last.nextCursor());
        assertEquals("from=2001-01-01T00:00:00Z&to=2001-01-01T01:00:00Z&cursor=21", lastQuery.get());
    }

    @Test
    void 문자열_nextCursor도_받는다() {
        body.set(CHANGES_FULL.replace("\"nextCursor\":21", "\"nextCursor\":\"21\""));

        assertEquals("21", client.findChanges(BASE, BASE.plusSeconds(3600), null).nextCursor());
    }

    @Test
    void 소수부가_0이_아닌_금액은_조회_실패다() {
        body.set(BY_IDS.replace("110000.00", "110000.50"));

        assertThrows(ReservationLookupException.class, () -> client.findByIds(List.of(11L)));
    }

    @Test
    void 감싸지_않은_옛_가정_모양은_조회_실패다() {
        body.set("{\"items\":[]}");

        assertThrows(ReservationLookupException.class, () -> client.findByIds(List.of(11L)));
    }
}
