package com.iseonjwa.mockpg.payment;

import com.iseonjwa.mockpg.fault.FaultInjector;
import com.iseonjwa.mockpg.fault.FaultRules;
import com.iseonjwa.mockpg.fault.FaultTarget;
import org.apache.tomcat.util.threads.VirtualThreadExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 긴 장애 지연이 요청 처리 스레드를 붙잡아도 다른 요청이 막히지 않도록, 요청 처리 실행기가 가상 스레드인지 확인한다(mock-pg.md 5절).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LongDelayConcurrencyTest {
    private static final int CONCURRENT_REQUESTS = 30;
    private static final long DELAY_MILLIS = 500;

    @LocalServerPort
    int port;

    @Autowired
    FaultInjector faults;

    @Autowired
    ServletWebServerApplicationContext context;

    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void clearFaults() {
        faults.apply(FaultRules.off(FaultTarget.APPROVE));
    }

    @Test
    void Tomcat_요청_처리_실행기가_가상_스레드_실행기다() {
        var webServer = assertInstanceOf(TomcatWebServer.class, context.getWebServer());

        var executor = webServer.getTomcat().getConnector().getProtocolHandler().getExecutor();

        assertInstanceOf(VirtualThreadExecutor.class, executor, String.valueOf(executor));
    }

    @Test
    void 지연을_건_승인이_진행_중이어도_다른_요청들이_함께_처리된다() throws Exception {
        faults.apply(FaultRules.delay(FaultTarget.APPROVE, DELAY_MILLIS, null));
        var startedAt = System.nanoTime();
        var responses = new ArrayList<CompletableFuture<HttpResponse<String>>>();

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            var body = "{\"orderId\":\"" + UUID.randomUUID() + "\",\"amount\":50000,\"currency\":\"KRW\"}";
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mock-pg/payments"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            responses.add(http.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
        }
        for (var response : responses) {
            assertEquals(200, response.get(5, TimeUnit.SECONDS).statusCode());
        }

        var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
        assertTrue(elapsed.toMillis() < 1_500, "30건이 지연 시간 안팎에 끝나지 않았다 elapsedMs=" + elapsed.toMillis());
    }
}
