package com.iseonjwa.payment.pg.infrastructure;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 실제 mock-pg 앱이 만들 수 없는 응답을 내는 테스트용 가짜 서버다. 모든 요청에 본문 코드가 없는 404를 답한다.
 * 어댑터가 코드 없는 404를 {@code NotFound}가 아니라 {@code CLIENT_ERROR}로 해석하는지 확인하는 한 테스트에만 쓴다.
 *
 * <p>Note: 승인·환불·망취소 같은 프로토콜을 흉내 내지 않는다. 그런 계약은 {@link MockPgServerContractTest}가
 * 실제 서버로 확인한다(mock-pg.md 1절). 서버 쪽 규칙을 여기에 다시 구현하면 실제 서버와 어긋난다.
 */
class FakeMockPgServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    FakeMockPgServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            var bytes = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(404, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
