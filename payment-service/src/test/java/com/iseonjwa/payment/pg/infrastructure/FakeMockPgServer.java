package com.iseonjwa.payment.pg.infrastructure;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * pg-integration.md의 "Mock PG 프로토콜"(SA 11-5와 S-28의 금액·통화)을 따르는 테스트용 가짜 서버다.
 * 어댑터가 응답을 올바르게 해석하는지 확인하는 용도이고, 실제 Mock PG 서버(mock-pg 모듈)는 따로 만든다.
 * 장애 주입은 다음 호출 한 번에만 적용된다.
 *
 * <p>Note: 승인 저장과 망취소는 같은 락 안에서 판단한다. 처리 중인 승인이 있는 주문에 취소가 오면 CANCELED를
 * 주지 않고 처리 중(202)으로 답한다. 그렇지 않으면 "Prevented를 받았는데 뒤늦게 승인이 저장되는" 일이 생긴다(INV-8).
 */
class FakeMockPgServer implements AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    enum Fault {
        NONE, DECLINE, DELAY, DROP_RESPONSE, PROCESSING, BAD_REQUEST, SERVER_ERROR, MISSING_AMOUNT, WRONG_AMOUNT,
        FRACTIONAL_AMOUNT, OTHER_CONFLICT
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Object lock = new Object();
    private final Map<String, Map<String, Object>> payments = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> refunds = new ConcurrentHashMap<>();
    private final Map<String, Integer> approvalsByOrder = new ConcurrentHashMap<>();
    private final Map<String, Integer> refundsById = new ConcurrentHashMap<>();
    private final Map<String, String> approvalIdempotencyKeys = new ConcurrentHashMap<>();
    private final Set<String> canceledOrders = ConcurrentHashMap.newKeySet();
    private final Set<String> inFlightOrders = ConcurrentHashMap.newKeySet();
    private volatile Fault nextFault = Fault.NONE;
    private volatile Fault nextRefundFault = Fault.NONE;
    private volatile long delayMillis;
    private volatile boolean rejectNextRefund;
    private volatile boolean bareNotFound;

    FakeMockPgServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        // Note: 기본 실행기는 스레드 하나라 느린 응답 시나리오가 다음 요청까지 막는다
        server.setExecutor(executor);
        server.createContext("/mock-pg/payments", this::payments);
        server.createContext("/mock-pg/refunds", this::refunds);
        server.start();
    }

    String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    int approvalCount(String orderId) { return approvalsByOrder.getOrDefault(orderId, 0); }

    int refundCount(String refundId) { return refundsById.getOrDefault(refundId, 0); }

    String approvalIdempotencyKey(String orderId) { return approvalIdempotencyKeys.get(orderId); }

    void failNextApproval(Fault fault) { nextFault = fault; }

    void delayNextApproval(long millis) { nextFault = Fault.DELAY; delayMillis = millis; }

    void failNextRefund(Fault fault) { nextRefundFault = fault; }

    void rejectNextRefund() { rejectNextRefund = true; }

    // 조회 404를 "해당 주문 없음" 코드 없이 내려 경로 오류처럼 보이게 한다
    void answerLookupWithBareNotFound() { bareNotFound = true; }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void payments(HttpExchange exchange) throws IOException {
        var parts = exchange.getRequestURI().getPath().split("/");
        // /mock-pg/payments, /mock-pg/payments/{orderId}, /mock-pg/payments/{orderId}/cancel
        if (parts.length == 3 && exchange.getRequestMethod().equals("POST")) {
            approve(exchange);
        } else if (parts.length == 4 && exchange.getRequestMethod().equals("GET")) {
            var payment = payments.get(parts[3]);
            if (payment == null) send(exchange, 404, notFoundBody());
            else send(exchange, 200, payment);
        } else if (parts.length == 5 && parts[4].equals("cancel")) {
            cancel(exchange, parts[3]);
        } else {
            send(exchange, 404, Map.of());
        }
    }

    private Map<String, String> notFoundBody() {
        if (bareNotFound) {
            bareNotFound = false;
            return Map.of();
        }
        return Map.of("code", "NOT_FOUND");
    }

    private void approve(HttpExchange exchange) throws IOException {
        var request = read(exchange);
        var orderId = request.get("orderId").asString();
        approvalIdempotencyKeys.put(orderId, exchange.getRequestHeaders().getFirst("Idempotency-Key"));
        var fault = nextFault;
        nextFault = Fault.NONE;
        if (fault == Fault.BAD_REQUEST) { send(exchange, 400, Map.of("code", "INVALID_REQUEST")); return; }
        if (fault == Fault.SERVER_ERROR) { send(exchange, 500, Map.of()); return; }
        if (fault == Fault.PROCESSING) { send(exchange, 202, Map.of("status", "PROCESSING")); return; }
        if (fault == Fault.OTHER_CONFLICT) { send(exchange, 409, Map.of("code", "REQUEST_IN_PROGRESS")); return; }

        Map<String, Object> payment;
        synchronized (lock) {
            if (canceledOrders.contains(orderId)) {
                send(exchange, 409, Map.of("code", "ORDER_CANCELED"));
                return;
            }
            // 이미 승인된 주문의 재요청은 저장된 결과를 돌려준다(멱등)
            payment = payments.get(orderId);
            if (payment == null) inFlightOrders.add(orderId);
        }
        if (payment == null) {
            if (fault == Fault.DELAY) sleep(delayMillis);
            synchronized (lock) {
                payment = decide(orderId, request, fault);
                payments.put(orderId, payment);
                approvalsByOrder.merge(orderId, 1, Integer::sum);
                inFlightOrders.remove(orderId);
            }
        }
        if (fault == Fault.DROP_RESPONSE) {
            // 승인은 저장하고 응답만 버린다(E9). 연결을 응답 없이 닫는다
            exchange.close();
            return;
        }
        var response = new LinkedHashMap<>(payment);
        if (fault == Fault.MISSING_AMOUNT) { response.remove("amount"); response.remove("currency"); }
        send(exchange, 200, response);
    }

    private static Map<String, Object> decide(String orderId, JsonNode request, Fault fault) {
        var body = new LinkedHashMap<String, Object>();
        if (fault == Fault.DECLINE) {
            body.put("status", "DECLINED");
            body.put("declineCode", "CARD_DECLINED");
            return body;
        }
        var amount = request.get("amount").decimalValue();
        if (fault == Fault.WRONG_AMOUNT) amount = amount.subtract(BigDecimal.valueOf(1000));
        if (fault == Fault.FRACTIONAL_AMOUNT) amount = amount.subtract(new BigDecimal("0.5"));
        body.put("pgTxId", "pg-" + UUID.randomUUID());
        body.put("status", "APPROVED");
        body.put("amount", amount);
        body.put("currency", request.get("currency").asString());
        body.put("approvedAt", Instant.now().toString());
        return body;
    }

    private void cancel(HttpExchange exchange, String orderId) throws IOException {
        Map<String, ?> response;
        int status = 200;
        synchronized (lock) {
            var payment = payments.get(orderId);
            if (payment != null && "APPROVED".equals(payment.get("status"))) {
                response = payment;
            } else if (inFlightOrders.contains(orderId)) {
                // 승인이 처리 중이면 취소를 보장할 수 없다
                status = 202;
                response = Map.of("status", "PROCESSING");
            } else {
                canceledOrders.add(orderId);
                response = Map.of("status", "CANCELED");
            }
        }
        send(exchange, status, response);
    }

    private void refunds(HttpExchange exchange) throws IOException {
        var parts = exchange.getRequestURI().getPath().split("/");
        if (parts.length == 3 && exchange.getRequestMethod().equals("POST")) {
            refund(exchange);
        } else if (parts.length == 4 && exchange.getRequestMethod().equals("GET")) {
            var refund = refunds.get(parts[3]);
            if (refund == null) send(exchange, 404, Map.of("code", "NOT_FOUND"));
            else send(exchange, 200, refund);
        } else {
            send(exchange, 404, Map.of());
        }
    }

    private void refund(HttpExchange exchange) throws IOException {
        var request = read(exchange);
        var refundId = request.get("refundId").asString();
        var fault = nextRefundFault;
        nextRefundFault = Fault.NONE;
        if (rejectNextRefund) {
            rejectNextRefund = false;
            send(exchange, 422, Map.of("status", "REJECTED", "code", "NOT_REFUNDABLE"));
            return;
        }
        if (fault == Fault.PROCESSING) { send(exchange, 202, Map.of("status", "PROCESSING")); return; }
        var refund = refunds.computeIfAbsent(refundId, id -> {
            refundsById.merge(refundId, 1, Integer::sum);
            var body = new LinkedHashMap<String, Object>();
            body.put("refundTxId", "refund-" + UUID.randomUUID());
            body.put("pgTxId", request.get("pgTxId").asString());
            body.put("status", "REFUNDED");
            body.put("amount", request.get("amount").decimalValue());
            body.put("currency", request.get("currency").asString());
            body.put("refundedAt", Instant.now().toString());
            return body;
        });
        if (fault == Fault.DROP_RESPONSE) {
            exchange.close();
            return;
        }
        var response = new LinkedHashMap<>(refund);
        if (fault == Fault.MISSING_AMOUNT) { response.remove("amount"); response.remove("currency"); }
        send(exchange, 200, response);
    }

    private static JsonNode read(HttpExchange exchange) throws IOException {
        return JSON.readTree(exchange.getRequestBody().readAllBytes());
    }

    private static void send(HttpExchange exchange, int status, Map<String, ?> body) throws IOException {
        var bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
