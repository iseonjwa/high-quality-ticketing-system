package com.iseonjwa.payment.pg.infrastructure;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Mock PG의 원장 조회 API(mock-pg.md 9절) 클라이언트. 테스트 전용이다. 결제 장애 시나리오 테스트가 V1(승인 중복 없음)·
 * V2(환불 중복 없음)를 PG 쪽 원장으로 검증하는 데 쓴다. 호출 계수는 PG가 센 값이므로 호출자 쪽 기록과 독립이다.
 *
 * <p>Note: 원장 조회는 {@code test}·{@code loadtest} 프로필에서만 열린다. {@link MockPgProcess}는 test 프로필로 띄운다.
 */
public final class MockPgLedgerClient {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(20);
    private static final int PAGE_SIZE = 500;

    private final String baseUrl;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper mapper = JsonMapper.builder().build();

    public MockPgLedgerClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** 주문의 원장 기록. 호출도 상태도 없는 주문이면 비어 있다. */
    public Optional<OrderLedger> payment(String orderId) {
        var response = get("/mock-pg/ledger/payments/" + orderId);
        if (response.statusCode() == 404) return Optional.empty();
        requireOk(response);
        var node = read(response);
        var keys = node.get("idempotencyKeys");
        return Optional.of(new OrderLedger(
                node.get("orderId").asString(), node.get("status").asString(), text(node, "pgTxId"),
                node.get("approveCalls").asLong(), node.get("lookupCalls").asLong(), node.get("cancelCalls").asLong(),
                node.get("refundedTotal").decimalValue(), text(keys, "last"), keys.get("distinctCount").asInt()));
    }

    /** 한 결제의 환불 기록. 저장된 환불만 담는다. */
    public PaymentRefunds refunds(String pgTxId) {
        var response = get("/mock-pg/ledger/refunds?pgTxId=" + pgTxId);
        requireOk(response);
        var node = read(response);
        var refunds = new ArrayList<RefundLedger>();
        for (var refund : node.get("refunds")) {
            var keys = refund.get("idempotencyKeys");
            refunds.add(new RefundLedger(refund.get("refundId").asString(), refund.get("refundTxId").asString(),
                    refund.get("amount").decimalValue(), refund.get("currency").asString(),
                    refund.get("refundCalls").asLong(), refund.get("refundLookupCalls").asLong(),
                    text(keys, "last"), keys.get("distinctCount").asInt()));
        }
        return new PaymentRefunds(List.copyOf(refunds), node.get("refundCount").asInt(),
                node.get("refundedTotal").decimalValue());
    }

    public Summary summary() {
        var response = get("/mock-pg/ledger/summary");
        requireOk(response);
        var node = read(response);
        return new Summary(node.get("approvedCount").asLong(), node.get("declinedCount").asLong(),
                node.get("canceledCount").asLong(), node.get("refundCount").asLong(),
                node.get("approvedTotal").decimalValue(), node.get("refundedTotal").decimalValue(),
                node.get("inFlightCount").asLong(), node.get("orderCount").asLong());
    }

    /** 승인된 주문 전부를 쪽을 넘기며 모은다. V1의 "PG에만 있는 승인"을 결제 DB와 대조하는 데 쓴다. */
    public List<ApprovedOrder> approvedOrders() {
        var all = new ArrayList<ApprovedOrder>();
        String cursor = "0";
        while (cursor != null) {
            var response = get("/mock-pg/ledger/orders?status=APPROVED&limit=" + PAGE_SIZE + "&cursor=" + cursor);
            requireOk(response);
            var node = read(response);
            for (var order : node.get("orders")) {
                all.add(new ApprovedOrder(order.get("orderId").asString(), order.get("pgTxId").asString()));
            }
            cursor = node.get("nextCursor").isNull() ? null : node.get("nextCursor").asString();
        }
        return List.copyOf(all);
    }

    /** 원장과 장애 설정을 새 객체로 바꾼다. 테스트 사이 격리용이다. */
    public void reset() {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/mock-pg/reset"))
                .timeout(REQUEST_TIMEOUT).POST(HttpRequest.BodyPublishers.noBody()).build();
        requireStatus(send(request), 204);
    }

    /**
     * 처리 중인 승인({@code inFlightCount})이 0이 될 때까지 기다린다. 응답 시간이 지나 클라이언트가 포기한 뒤에도 PG 쪽에서
     * 진행 중이던 처리가 끝났는지 확인하는 데 쓴다. 시한 안에 0이 되지 않으면 실패한다. {@code sleep}으로 맞추지 않는다.
     */
    public void awaitNoInFlight(Duration timeout) {
        var deadline = System.nanoTime() + timeout.toNanos();
        long inFlight;
        while ((inFlight = summary().inFlightCount()) > 0) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("처리 중인 승인이 " + timeout + " 안에 끝나지 않았다 inFlightCount=" + inFlight);
            }
            sleep(POLL_INTERVAL);
        }
    }

    private HttpResponse<String> get(String path) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(REQUEST_TIMEOUT).GET().build());
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new IllegalStateException("Mock PG 원장 조회 호출 실패 " + request.uri(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Mock PG 원장 조회 중 인터럽트", exception);
        }
    }

    private static void requireOk(HttpResponse<String> response) {
        requireStatus(response, 200);
    }

    private static void requireStatus(HttpResponse<String> response, int expected) {
        if (response.statusCode() != expected) {
            throw new IllegalStateException("Mock PG 원장 조회 실패 status=" + response.statusCode()
                    + " uri=" + response.uri() + " body=" + response.body());
        }
    }

    private JsonNode read(HttpResponse<String> response) {
        return mapper.readTree(response.body());
    }

    private static String text(JsonNode node, String field) {
        var value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 주문의 원장 기록. {@code status}가 {@code NONE}이면 호출만 있고 저장된 상태가 없다.
     *
     * @param lastIdempotencyKey 승인 호출이 마지막으로 보낸 {@code Idempotency-Key}. 헤더가 없었으면 null이다
     */
    public record OrderLedger(String orderId, String status, String pgTxId, long approveCalls, long lookupCalls,
            long cancelCalls, BigDecimal refundedTotal, String lastIdempotencyKey, int distinctIdempotencyKeys) {
    }

    public record RefundLedger(String refundId, String refundTxId, BigDecimal amount, String currency, long refundCalls,
            long refundLookupCalls, String lastIdempotencyKey, int distinctIdempotencyKeys) {
    }

    public record PaymentRefunds(List<RefundLedger> refunds, int refundCount, BigDecimal refundedTotal) {
    }

    public record Summary(long approvedCount, long declinedCount, long canceledCount, long refundCount,
            BigDecimal approvedTotal, BigDecimal refundedTotal, long inFlightCount, long orderCount) {
    }

    public record ApprovedOrder(String orderId, String pgTxId) {
    }
}
