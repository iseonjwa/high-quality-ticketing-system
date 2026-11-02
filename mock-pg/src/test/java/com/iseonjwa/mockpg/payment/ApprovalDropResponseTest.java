package com.iseonjwa.mockpg.payment;

import com.iseonjwa.mockpg.fault.FaultInjector;
import com.iseonjwa.mockpg.fault.FaultMode;
import com.iseonjwa.mockpg.fault.FaultRule;
import com.iseonjwa.mockpg.fault.FaultTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 응답 유실(DROP_RESPONSE)이 "저장은 끝났고 응답만 0바이트로 사라진" 상태를 만드는지 소켓 수준에서 확인한다(mock-pg.md 6절).
 * HTTP 클라이언트는 받은 바이트를 숨기므로 원시 소켓으로 읽는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApprovalDropResponseTest {
    private static final int SOCKET_READ_TIMEOUT_MILLIS = 5_000;

    @LocalServerPort
    int port;

    @Autowired
    FaultInjector faults;

    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void clearFaults() {
        faults.apply(new FaultRule(FaultTarget.APPROVE, FaultMode.NONE, null));
    }

    @Test
    void 응답_유실을_걸면_승인_요청에_한_바이트도_보내지_않고_연결을_닫는다() throws IOException {
        faults.apply(new FaultRule(FaultTarget.APPROVE, FaultMode.DROP_RESPONSE, 1));

        // 시한 안에 -1이면 상태 줄도 받지 못한 채 서버가 연결을 닫은 것이다. 시한이 지나면 SocketTimeoutException으로 실패한다
        assertEquals(-1, sendRawApproval(UUID.randomUUID().toString()), "응답 바이트를 받았다");
    }

    @Test
    void 응답을_잃은_승인도_저장은_끝나서_조회하면_승인이다() throws Exception {
        var orderId = UUID.randomUUID().toString();
        faults.apply(new FaultRule(FaultTarget.APPROVE, FaultMode.DROP_RESPONSE, 1));
        assertEquals(-1, sendRawApproval(orderId), "응답 바이트를 받았다");

        var found = get("/mock-pg/payments/" + orderId);

        assertEquals(200, found.statusCode());
        assertTrue(found.body().contains("\"status\":\"APPROVED\""), found.body());
    }

    @Test
    void 횟수를_다_쓴_응답_유실은_다음_승인에_적용되지_않는다() throws Exception {
        faults.apply(new FaultRule(FaultTarget.APPROVE, FaultMode.DROP_RESPONSE, 1));
        sendRawApproval(UUID.randomUUID().toString());

        assertTrue(sendRawApproval(UUID.randomUUID().toString()) != -1, "두 번째 승인도 응답을 잃었다");
    }

    // 승인 요청을 원시 소켓으로 보내고 응답의 첫 바이트를 돌려준다. 연결이 응답 없이 닫혔으면 -1이다
    private int sendRawApproval(String orderId) throws IOException {
        var body = ("{\"orderId\":\"" + orderId + "\",\"amount\":50000,\"currency\":\"KRW\"}")
                .getBytes(StandardCharsets.UTF_8);
        var head = ("POST /mock-pg/payments HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + port + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "\r\n").getBytes(StandardCharsets.US_ASCII);
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(SOCKET_READ_TIMEOUT_MILLIS);
            var out = socket.getOutputStream();
            out.write(head);
            out.write(body);
            out.flush();
            return socket.getInputStream().read();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
