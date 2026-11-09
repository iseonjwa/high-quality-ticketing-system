package com.iseonjwa.payment.pg.infrastructure;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 토스페이먼츠 결제 API(confirm, 주문·결제 조회, 취소)를 흉내 내는 테스트용 가짜 서버다. 토스가 아직 확인되지 않은 동작은
 * 공식 문서에 적힌 대로만 따른다. 실제 토스 호출은 {@code toss-sandbox} 태그 테스트가 맡는다.
 *
 * <p>Note: Basic 인증 값을 검사하고, 같은 {@code Idempotency-Key}의 요청에는 첫 응답을 그대로 돌려준다(15일 유효는
 * 흉내 내지 않는다). 장애 주입은 다음 호출 한 번에만 적용된다. 승인·취소의 효과는 응답을 잃어도 원장에 남는다.
 */
class FakeTossServer implements AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    enum Fault {
        NONE, DECLINE, DELAY, DROP_RESPONSE, PROCESSING, BAD_REQUEST, SERVER_ERROR, MISSING_AMOUNT, WRONG_AMOUNT,
        FRACTIONAL_AMOUNT, PROVIDER_ERROR, ALREADY_PROCESSED, SESSION_EXPIRED, WRONG_PAYMENT_KEY, UNKNOWN_ERROR_CODE,
        REJECT
    }

    private record Stored(int status, Map<String, ?> body) {}

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Object lock = new Object();
    private final String expectedAuthorization;
    private final Map<String, Map<String, Object>> paymentsByOrder = new ConcurrentHashMap<>();
    private final Map<String, String> orderByPaymentKey = new ConcurrentHashMap<>();
    private final Map<String, Stored> replays = new ConcurrentHashMap<>();
    private final Map<String, Integer> confirmsByOrder = new ConcurrentHashMap<>();
    private final Map<String, Integer> cancelsByKey = new ConcurrentHashMap<>();
    private final Map<String, String> confirmIdempotencyKeys = new ConcurrentHashMap<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private volatile JsonNode lastConfirmRequest;
    private volatile JsonNode lastCancelRequest;
    private volatile Fault nextFault = Fault.NONE;
    private volatile Fault nextRefundFault = Fault.NONE;
    private volatile long delayMillis;

    FakeTossServer(String secretKey) {
        expectedAuthorization = "Basic "
                + Base64.getEncoder().encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        // Note: 기본 실행기는 스레드 하나라 느린 응답 시나리오가 다음 요청까지 막는다
        server.setExecutor(executor);
        server.createContext("/v1/payments", this::route);
        server.start();
    }

    String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    int confirmCount(String orderId) { return confirmsByOrder.getOrDefault(orderId, 0); }

    int cancelCount(String idempotencyKey) { return cancelsByKey.getOrDefault(idempotencyKey, 0); }

    String confirmIdempotencyKey(String orderId) { return confirmIdempotencyKeys.get(orderId); }

    List<String> authorizations() { return List.copyOf(authorizations); }

    JsonNode lastConfirmRequest() { return lastConfirmRequest; }

    JsonNode lastCancelRequest() { return lastCancelRequest; }

    void failNextConfirm(Fault fault) { nextFault = fault; }

    void delayNextConfirm(long millis) { nextFault = Fault.DELAY; delayMillis = millis; }

    void failNextCancel(Fault fault) { nextRefundFault = fault; }

    // 주문의 결제 상태를 직접 바꾼다(EXPIRED, CANCELED, IN_PROGRESS 같은 조회 시나리오용)
    void forceStatus(String orderId, String status) {
        synchronized (lock) {
            paymentsByOrder.get(orderId).put("status", status);
        }
    }

    // 승인 이력 없이 IN_PROGRESS 결제를 만든다(인증만 끝난 상태)
    void seedInProgress(String orderId, String paymentKey) {
        var payment = new LinkedHashMap<String, Object>();
        payment.put("status", "IN_PROGRESS");
        payment.put("paymentKey", paymentKey);
        payment.put("orderId", orderId);
        payment.put("cancels", List.of());
        synchronized (lock) {
            paymentsByOrder.put(orderId, payment);
            orderByPaymentKey.put(paymentKey, orderId);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void route(HttpExchange exchange) throws IOException {
        var authorization = exchange.getRequestHeaders().getFirst("Authorization");
        authorizations.add(authorization);
        if (!expectedAuthorization.equals(authorization)) {
            send(exchange, 401, error("UNAUTHORIZED_KEY", "인증되지 않은 시크릿 키 혹은 클라이언트 키 입니다."));
            return;
        }
        var parts = exchange.getRequestURI().getPath().split("/");
        var method = exchange.getRequestMethod();
        // /v1/payments/confirm, /v1/payments/orders/{orderId}, /v1/payments/{paymentKey}, /v1/payments/{paymentKey}/cancel
        if (parts.length == 4 && parts[3].equals("confirm") && method.equals("POST")) {
            confirm(exchange);
        } else if (parts.length == 5 && parts[3].equals("orders") && method.equals("GET")) {
            byOrder(exchange, parts[4]);
        } else if (parts.length == 4 && method.equals("GET")) {
            byPaymentKey(exchange, parts[3]);
        } else if (parts.length == 5 && parts[4].equals("cancel") && method.equals("POST")) {
            cancel(exchange, parts[3]);
        } else {
            send(exchange, 404, Map.of());
        }
    }

    private void confirm(HttpExchange exchange) throws IOException {
        var request = read(exchange);
        lastConfirmRequest = request;
        var idempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        var orderId = text(request, "orderId");
        var paymentKey = text(request, "paymentKey");
        if (orderId == null || orderId.length() < 6 || orderId.length() > 64 || paymentKey == null
                || paymentKey.length() > 200 || !request.has("amount")) {
            send(exchange, 400, error("INVALID_REQUEST", "요청 본문이 올바르지 않습니다."));
            return;
        }
        confirmIdempotencyKeys.put(orderId, idempotencyKey);
        var fault = nextFault;
        nextFault = Fault.NONE;

        var replay = idempotencyKey == null ? null : replays.get("confirm:" + idempotencyKey);
        if (replay != null) {
            send(exchange, replay.status(), replay.body());
            return;
        }
        var rejection = rejection(fault);
        if (rejection != null) {
            send(exchange, rejection.status(), rejection.body());
            return;
        }

        if (fault == Fault.DELAY) sleep(delayMillis);
        Stored stored;
        synchronized (lock) {
            stored = decide(orderId, paymentKey, request, fault);
            if (idempotencyKey != null) replays.put("confirm:" + idempotencyKey, stored);
        }
        if (fault == Fault.DROP_RESPONSE) {
            // 승인은 저장하고 응답만 버린다. 연결을 응답 없이 닫는다
            exchange.close();
            return;
        }
        var response = new LinkedHashMap<String, Object>(stored.body());
        if (fault == Fault.MISSING_AMOUNT) { response.remove("totalAmount"); response.remove("currency"); }
        if (fault == Fault.WRONG_PAYMENT_KEY) response.put("paymentKey", "other_" + paymentKey);
        send(exchange, stored.status(), response);
    }

    // 승인 전에 토스가 돌려주는 오류. 원장에는 아무것도 남기지 않는다
    private static Stored rejection(Fault fault) {
        return switch (fault) {
            case BAD_REQUEST -> new Stored(400, error("INVALID_REQUEST", "잘못된 요청입니다."));
            case SERVER_ERROR -> new Stored(500, error("FAILED_INTERNAL_SYSTEM_PROCESSING", "내부 시스템 처리 작업이 실패했습니다."));
            case PROCESSING -> new Stored(409, error("IDEMPOTENT_REQUEST_PROCESSING", "이전 요청이 처리 중입니다."));
            case PROVIDER_ERROR -> new Stored(400, error("PROVIDER_ERROR", "일시적인 오류가 발생했습니다."));
            case ALREADY_PROCESSED -> new Stored(400, error("ALREADY_PROCESSED_PAYMENT", "이미 처리된 결제 입니다."));
            case SESSION_EXPIRED -> new Stored(404, error("NOT_FOUND_PAYMENT_SESSION", "결제 시간이 만료되어 결제 진행 데이터가 존재하지 않습니다."));
            case UNKNOWN_ERROR_CODE -> new Stored(400, error("SOMETHING_NEW", "처음 보는 오류입니다."));
            default -> null;
        };
    }

    private Stored decide(String orderId, String paymentKey, JsonNode request, Fault fault) {
        var existing = paymentsByOrder.get(orderId);
        if (existing != null && !"IN_PROGRESS".equals(existing.get("status"))) {
            // 다른 멱등키로 이미 처리된 주문을 다시 승인하면 토스는 오류로 답한다
            return new Stored(400, error("ALREADY_PROCESSED_PAYMENT", "이미 처리된 결제 입니다."));
        }
        var payment = new LinkedHashMap<String, Object>();
        payment.put("paymentKey", paymentKey);
        payment.put("orderId", orderId);
        payment.put("cancels", new ArrayList<>());
        if (fault == Fault.DECLINE) {
            payment.put("status", "ABORTED");
            payment.put("failure", Map.of("code", "REJECT_CARD_COMPANY", "message", "카드사가 거절했습니다."));
            paymentsByOrder.put(orderId, payment);
            orderByPaymentKey.put(paymentKey, orderId);
            return new Stored(403, error("REJECT_CARD_COMPANY", "결제 승인이 거절되었습니다."));
        }
        var amount = request.get("amount").decimalValue();
        if (fault == Fault.WRONG_AMOUNT) amount = amount.subtract(BigDecimal.valueOf(1000));
        if (fault == Fault.FRACTIONAL_AMOUNT) amount = amount.subtract(new BigDecimal("0.5"));
        payment.put("status", "DONE");
        payment.put("totalAmount", amount);
        payment.put("currency", "KRW");
        payment.put("approvedAt", now());
        paymentsByOrder.put(orderId, payment);
        orderByPaymentKey.put(paymentKey, orderId);
        confirmsByOrder.merge(orderId, 1, Integer::sum);
        return new Stored(200, snapshot(payment));
    }

    private void byOrder(HttpExchange exchange, String orderId) throws IOException {
        Map<String, Object> payment;
        synchronized (lock) {
            var found = paymentsByOrder.get(orderId);
            payment = found == null ? null : snapshot(found);
        }
        respondWithPayment(exchange, payment);
    }

    private void byPaymentKey(HttpExchange exchange, String paymentKey) throws IOException {
        Map<String, Object> payment = null;
        synchronized (lock) {
            var orderId = orderByPaymentKey.get(paymentKey);
            if (orderId != null) payment = snapshot(paymentsByOrder.get(orderId));
        }
        respondWithPayment(exchange, payment);
    }

    private void respondWithPayment(HttpExchange exchange, Map<String, Object> payment) throws IOException {
        if (payment == null) send(exchange, 404, error("NOT_FOUND_PAYMENT", "존재하지 않는 결제 정보 입니다."));
        else send(exchange, 200, payment);
    }

    private void cancel(HttpExchange exchange, String paymentKey) throws IOException {
        var request = read(exchange);
        lastCancelRequest = request;
        var idempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        var fault = nextRefundFault;
        nextRefundFault = Fault.NONE;
        if (text(request, "cancelReason") == null) {
            send(exchange, 400, error("INVALID_REQUEST", "cancelReason이 필요합니다."));
            return;
        }
        var replay = idempotencyKey == null ? null : replays.get("cancel:" + idempotencyKey);
        if (replay != null) {
            send(exchange, replay.status(), replay.body());
            return;
        }
        if (fault == Fault.PROCESSING) {
            send(exchange, 409, error("IDEMPOTENT_REQUEST_PROCESSING", "이전 요청이 처리 중입니다."));
            return;
        }
        if (fault == Fault.REJECT) {
            send(exchange, 403, error("NOT_CANCELABLE_PAYMENT", "취소 할 수 없는 결제 입니다."));
            return;
        }

        Stored stored;
        synchronized (lock) {
            var orderId = orderByPaymentKey.get(paymentKey);
            var payment = orderId == null ? null : paymentsByOrder.get(orderId);
            if (payment == null) {
                stored = new Stored(404, error("NOT_FOUND_PAYMENT", "존재하지 않는 결제 정보 입니다."));
            } else if (!"DONE".equals(payment.get("status"))) {
                stored = new Stored(400, error("ALREADY_CANCELED_PAYMENT", "이미 취소된 결제 입니다."));
            } else {
                var amount = request.has("cancelAmount") ? request.get("cancelAmount").decimalValue()
                        : (BigDecimal) payment.get("totalAmount");
                var cancel = new LinkedHashMap<String, Object>();
                cancel.put("transactionKey", "tx_" + UUID.randomUUID().toString().replace("-", ""));
                cancel.put("cancelReason", text(request, "cancelReason"));
                cancel.put("cancelAmount", amount);
                cancel.put("canceledAt", now());
                cancel.put("cancelStatus", "DONE");
                @SuppressWarnings("unchecked")
                var cancels = (List<Object>) payment.get("cancels");
                cancels.add(cancel);
                payment.put("status", "CANCELED");
                if (idempotencyKey != null) cancelsByKey.merge(idempotencyKey, 1, Integer::sum);
                stored = new Stored(200, snapshot(payment));
            }
            if (idempotencyKey != null) replays.put("cancel:" + idempotencyKey, stored);
        }
        if (fault == Fault.DROP_RESPONSE) {
            exchange.close();
            return;
        }
        var response = new LinkedHashMap<String, Object>(stored.body());
        if (fault == Fault.MISSING_AMOUNT) {
            response.remove("currency");
            response.put("cancels", withoutCancelAmount(response.get("cancels")));
        }
        send(exchange, stored.status(), response);
    }

    private static List<Object> withoutCancelAmount(Object cancels) {
        var result = new ArrayList<Object>();
        if (cancels instanceof List<?> list) {
            for (var item : list) {
                var copy = new LinkedHashMap<String, Object>();
                if (item instanceof Map<?, ?> map) map.forEach((key, value) -> copy.put(key.toString(), value));
                copy.remove("cancelAmount");
                result.add(copy);
            }
        }
        return result;
    }

    // 응답을 보내는 동안 원장이 바뀌지 않도록 복사본을 쓴다
    private static Map<String, Object> snapshot(Map<String, Object> payment) {
        var copy = new LinkedHashMap<String, Object>(payment);
        copy.put("cancels", new ArrayList<>((List<?>) payment.get("cancels")));
        return copy;
    }

    private static String now() {
        return ISO.format(OffsetDateTime.now(ZoneOffset.ofHours(9)).withNano(0));
    }

    private static Map<String, Object> error(String code, String message) {
        return Map.of("code", code, "message", message);
    }

    private static String text(JsonNode node, String field) {
        var value = node.get(field);
        return value == null || !value.isString() ? null : value.asString();
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
