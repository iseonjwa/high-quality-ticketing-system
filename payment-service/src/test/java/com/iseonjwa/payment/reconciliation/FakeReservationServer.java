package com.iseonjwa.payment.reconciliation;

import com.iseonjwa.payment.reconciliation.domain.ReservationSnapshot;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 예매 서비스의 대사용 내부 조회 API(contracts.md, ISE-75 확정 전 가정)를 흉내 내는 테스트용 가짜 서버다. 예매와 변경 목록을 정하고,
 * 응답 코드·지연·읽을 수 없는 값을 걸 수 있다. 실제 예매 서비스는 아직 없다. 장애 설정은 {@link #reset()}까지 계속 적용된다.
 */
public class FakeReservationServer implements AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String BASE = "/internal/reconciliation/reservations";

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<Long, ReservationSnapshot> reservations = new ConcurrentHashMap<>();
    private final Map<Long, String> corruptedStatus = new ConcurrentHashMap<>();
    private final List<ReservationSnapshot> changes = new CopyOnWriteArrayList<>();
    private final List<Integer> byIdBatchSizes = new CopyOnWriteArrayList<>();
    private final List<Map<String, String>> changeRequests = new CopyOnWriteArrayList<>();
    private volatile int forcedStatus;
    private volatile long delayMillis;
    private volatile boolean fractionalAmounts;

    public FakeReservationServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        // Note: 기본 실행기는 스레드 하나라 느린 응답이 다음 요청까지 막는다
        server.setExecutor(executor);
        server.createContext(BASE, this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    // ----- 데이터

    public void put(ReservationSnapshot reservation) {
        reservations.put(reservation.reservationId(), reservation);
    }

    public void remove(long reservationId) {
        reservations.remove(reservationId);
    }

    public ReservationSnapshot snapshot(long reservationId) {
        return reservations.get(reservationId);
    }

    /** 호출 기록만 지운다. 예매 데이터와 장애 설정은 그대로다. */
    public void resetCallCounts() {
        byIdBatchSizes.clear();
        changeRequests.clear();
    }

    /** 변경 목록으로 돌려줄 예매. 넣은 순서대로 쪽을 나눈다. */
    public void setChanges(List<ReservationSnapshot> items) {
        changes.clear();
        changes.addAll(items);
    }

    // ----- 장애

    /** 모든 요청에 이 코드로 답한다(200이 아닌 코드). */
    public void respondWith(int status) {
        forcedStatus = status;
    }

    public void delayResponses(long millis) {
        delayMillis = millis;
    }

    /** 이 예매의 status를 알 수 없는 값으로 내린다. */
    public void corruptStatusOf(long reservationId) {
        corruptedStatus.put(reservationId, "UNKNOWN_STATE");
    }

    /** 금액을 소수로 내린다. */
    public void answerWithFractionalAmounts() {
        fractionalAmounts = true;
    }

    public void reset() {
        reservations.clear();
        corruptedStatus.clear();
        changes.clear();
        byIdBatchSizes.clear();
        changeRequests.clear();
        forcedStatus = 0;
        delayMillis = 0;
        fractionalAmounts = false;
    }

    /** 묶음 조회 호출 하나하나의 ID 수. */
    public List<Integer> byIdBatchSizes() {
        return List.copyOf(byIdBatchSizes);
    }

    public List<Map<String, String>> changeRequests() {
        return List.copyOf(changeRequests);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    // ----- 처리

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            var query = query(exchange);
            var isChanges = exchange.getRequestURI().getPath().equals(BASE + "/changes");
            // 호출 기록은 장애 응답이어도 남긴다
            if (isChanges) changeRequests.add(query);
            else if (query.containsKey("ids")) byIdBatchSizes.add(query.get("ids").split(",").length);
            if (delayMillis > 0) sleep(delayMillis);
            if (forcedStatus != 0) {
                reply(exchange, forcedStatus, "{\"error\":\"forced\"}");
                return;
            }
            if (isChanges) {
                reply(exchange, 200, changesBody(query));
            } else if (query.containsKey("ids")) {
                reply(exchange, 200, byIdBody(query.get("ids")));
            } else {
                reply(exchange, 404, "{}");
            }
        }
    }

    private String byIdBody(String ids) {
        var items = new ArrayList<Map<String, Object>>();
        for (var id : ids.split(",")) {
            var reservation = reservations.get(Long.parseLong(id.trim()));
            // 없는 ID는 items에서 빠진다
            if (reservation != null) items.add(item(reservation));
        }
        return JSON.writeValueAsString(Map.of("items", items));
    }

    private String changesBody(Map<String, String> query) {
        var size = Integer.parseInt(query.getOrDefault("size", "100"));
        var from = query.containsKey("cursor") ? Integer.parseInt(query.get("cursor")) : 0;
        var to = Math.min(from + size, changes.size());
        var items = new ArrayList<Map<String, Object>>();
        for (var i = from; i < to; i++) items.add(item(changes.get(i)));
        var body = new LinkedHashMap<String, Object>();
        body.put("items", items);
        body.put("nextCursor", to < changes.size() ? String.valueOf(to) : null);
        return JSON.writeValueAsString(body);
    }

    private Map<String, Object> item(ReservationSnapshot reservation) {
        var item = new LinkedHashMap<String, Object>();
        item.put("reservationId", reservation.reservationId());
        item.put("userId", reservation.userId());
        item.put("status", corruptedStatus.getOrDefault(reservation.reservationId(), reservation.status().name()));
        // 삼항식에 long과 double을 섞으면 항상 double이 되므로 Object로 나눠 넣는다
        if (fractionalAmounts) item.put("totalAmount", reservation.totalAmount() + 0.5);
        else item.put("totalAmount", reservation.totalAmount());
        item.put("paymentId", reservation.paymentId());
        item.put("holdExpiresAt", reservation.holdExpiresAt() == null ? null : reservation.holdExpiresAt().toString());
        item.put("updatedAt", reservation.updatedAt().toString());
        return item;
    }

    private static Map<String, String> query(HttpExchange exchange) {
        var result = new LinkedHashMap<String, String>();
        var raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) return result;
        for (var pair : raw.split("&")) {
            var parts = pair.split("=", 2);
            result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return result;
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /** 테스트 편의: 상태만 정해 스냅숏을 만든다. */
    public static ReservationSnapshot reservation(long id, long userId, com.iseonjwa.payment.reconciliation.domain.ReservationStatus status,
            long totalAmount, Long paymentId, Instant updatedAt) {
        return new ReservationSnapshot(id, userId, status, totalAmount, paymentId, null, updatedAt);
    }
}
