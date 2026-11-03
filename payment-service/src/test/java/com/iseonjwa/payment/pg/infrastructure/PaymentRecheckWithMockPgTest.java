package com.iseonjwa.payment.pg.infrastructure;

import com.iseonjwa.payment.support.AppendOnlyCleanup;
import com.iseonjwa.payment.payment.application.PaymentRecheckService;
import com.iseonjwa.payment.payment.domain.PaymentRepository;
import com.iseonjwa.payment.support.RecheckFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static com.iseonjwa.payment.support.PaymentTokenFixtures.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E9: 승인 응답이 유실돼도 재확인이 PG를 조회해 확정한다. 실제 mock-pg(별도 프로세스)에 응답 유실을 걸고 승인 API를 부른 뒤,
 * 재확인 서비스를 직접 불러 확정까지 이어지는지 본다(events-and-reliability.md 6절).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class PaymentRecheckWithMockPgTest {
    private static final long USER = 61L;
    private static final long AMOUNT = 50000L;
    private static final long FIRST_RESERVATION = 8_600_000;
    private static final AtomicLong RESERVATION_IDS = new AtomicLong(FIRST_RESERVATION);

    // 스프링 컨텍스트가 만들어지기 전에 mock-pg가 떠 있어야 base-url을 넘길 수 있다
    private static final MockPgProcess SERVER = MockPgProcess.start();

    @DynamicPropertySource
    static void mockPgUrl(DynamicPropertyRegistry registry) {
        registry.add("payment.pg.mock.base-url", SERVER::baseUrl);
    }

    @LocalServerPort
    int port;
    @Autowired
    PaymentRecheckService recheck;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PaymentRepository payments;

    private RecheckFixtures fixtures;

    private final HttpClient http = HttpClient.newHttpClient();

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    @BeforeEach
    void setUp() {
        fixtures = new RecheckFixtures(payments, jdbc);
    }

    @AfterEach
    void cleanUp() {
        SERVER.applyFault("APPROVE", "NONE", null);
        jdbc.update("DELETE FROM idempotency_records WHERE resource_id IN (SELECT id FROM payments WHERE reservation_id >= ?)",
                FIRST_RESERVATION);
        jdbc.update("DELETE FROM outbox_events WHERE message_key ~ '^86[0-9]{5}$'");
        AppendOnlyCleanup.deleteHistories(jdbc, "payment_id IN (SELECT id FROM payments WHERE reservation_id >= ?)",
                FIRST_RESERVATION);
        jdbc.update("DELETE FROM payments WHERE reservation_id >= ?", FIRST_RESERVATION);
    }

    @Test
    void 승인_응답이_유실되면_UNKNOWN으로_두었다가_재확인이_승인으로_확정하고_이벤트를_낸다() throws Exception {
        var reservation = RESERVATION_IDS.incrementAndGet();
        SERVER.applyFault("APPROVE", "DROP_RESPONSE", 1);

        var response = approve(token(USER, reservation, AMOUNT).build());

        assertEquals(202, response.statusCode(), response.body());
        assertEquals("UNKNOWN", status(reservation));
        assertEquals(0, outboxCount(reservation), "확정 전에는 결과 이벤트가 없다");

        // 첫 재확인은 10초 뒤다. 기다리지 않고 차례가 된 것으로 만든다
        jdbc.update("UPDATE payments SET next_check_at = now() - interval '1 second' WHERE reservation_id = ?", reservation);
        fixtures.parkAllExcept(paymentId(reservation));
        assertTrue(recheck.recheckNext());

        assertEquals("APPROVED", status(reservation));
        assertEquals(1, outboxCount(reservation));
        assertEquals("PaymentApproved", jdbc.queryForObject(
                "SELECT event_type FROM outbox_events WHERE message_key = ?", String.class, String.valueOf(reservation)));
        assertEquals(1, jdbc.queryForObject("SELECT unknown_retry_count FROM payments WHERE reservation_id = ?",
                Integer.class, reservation));
    }

    private long paymentId(long reservation) {
        return jdbc.queryForObject("SELECT id FROM payments WHERE reservation_id = ?", Long.class, reservation);
    }

    private String status(long reservation) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE reservation_id = ?", String.class, reservation);
    }

    private int outboxCount(long reservation) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE message_key = ?", Integer.class,
                String.valueOf(reservation));
    }

    private HttpResponse<String> approve(String paymentToken) throws Exception {
        var body = "{\"paymentToken\":\"" + paymentToken + "\",\"amount\":" + AMOUNT + ",\"method\":\"CARD\"}";
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/payments"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("X-User-Id", Long.toString(USER)).header("X-User-Role", "USER")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
