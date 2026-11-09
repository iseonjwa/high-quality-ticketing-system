package com.iseonjwa.payment.payment.presentation;

import com.iseonjwa.payment.pg.application.ApproveCommand;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgOrderIds;
import com.iseonjwa.payment.pg.application.PgPaymentRef;
import com.iseonjwa.payment.pg.application.UnknownCause;
import com.iseonjwa.payment.pg.infrastructure.Resilience4jPgApprovalGate;
import com.iseonjwa.payment.support.AppendOnlyCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static com.iseonjwa.payment.support.PaymentTokenFixtures.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 토스 모드의 승인 API와 checkout API를 HTTP로 확인한다(ISE-45, ADR 0023 개정). PG는 PgClient 포트를 대체한다.
// 키는 형식만 맞춘 시험용 값이다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
                "payment.pg.provider=toss", "payment.pg.toss.secret-key=test_sk_api_test_dummy",
                "payment.pg.toss.client-key=test_ck_api_test_dummy"})
@ActiveProfiles("test")
class PaymentTossModeApiTest {
    private static final long USER = 61L;
    private static final long AMOUNT = 50000L;
    private static final long FIRST_RESERVATION = 8_600_000;
    private static final AtomicLong RESERVATION_IDS = new AtomicLong(FIRST_RESERVATION);

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Resilience4jPgApprovalGate approvalGate;
    @MockitoBean
    PgClient pg;

    @BeforeEach
    void stubPg() {
        reset(pg);
        approvalGate.circuitBreaker().reset();
        when(pg.approve(any())).thenAnswer(invocation -> approved(invocation.getArgument(0)));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM idempotency_records WHERE resource_id IN (SELECT id FROM payments WHERE reservation_id >= ?)",
                FIRST_RESERVATION);
        jdbc.update("DELETE FROM outbox_events WHERE message_key ~ '^86[0-9]{5}$'");
        AppendOnlyCleanup.deleteHistories(jdbc, "payment_id IN (SELECT id FROM payments WHERE reservation_id >= ?)",
                FIRST_RESERVATION);
        jdbc.update("DELETE FROM payments WHERE reservation_id >= ?", FIRST_RESERVATION);
    }

    @Test
    void checkout은_orderId_클라이언트_키_금액_주문명을_돌려주고_시크릿_키는_내지_않는다() throws Exception {
        var reservation = newReservation();
        var expiresAt = Instant.now().plusSeconds(290);
        var token = token(USER, reservation, AMOUNT).expiresAt(expiresAt).build();

        var response = checkout(USER, token);

        assertEquals(200, response.status());
        var data = response.json().get("data");
        assertEquals("TOSS", data.get("provider").asString());
        assertEquals(PgOrderIds.of(reservation, expiresAt.truncatedTo(ChronoUnit.SECONDS)).value().toString(),
                data.get("orderId").asString());
        assertEquals("test_ck_api_test_dummy", data.get("clientKey").asString());
        assertEquals(AMOUNT, data.get("amount").asLong());
        assertTrue(data.get("orderName").asString().contains(Long.toString(reservation)));
        assertFalse(response.body().contains("test_sk_"), "응답에 시크릿 키가 있다");
        assertEquals(0, paymentCount(reservation), "checkout은 아무것도 저장하지 않는다");
    }

    @Test
    void checkout의_orderId와_TX1의_pg_order_id와_PG_호출의_orderId가_같다() throws Exception {
        var reservation = newReservation();
        var token = token(USER, reservation, AMOUNT).build();
        var checkoutOrderId = checkout(USER, token).json().get("data").get("orderId").asString();

        var response = approve(USER, key(), token, "tgen_same_order");

        assertEquals(200, response.status());
        assertEquals(checkoutOrderId, jdbc.queryForObject("SELECT pg_order_id::text FROM payments WHERE reservation_id = ?",
                String.class, reservation));
        var captured = ArgumentCaptor.forClass(ApproveCommand.class);
        verify(pg).approve(captured.capture());
        assertEquals(checkoutOrderId, captured.getValue().orderId().value().toString());
    }

    @Test
    void PG를_부르는_시점에_paymentKey가_이미_pg_tx_id에_저장돼_있고_PG에도_전달된다() throws Exception {
        var reservation = newReservation();
        var stored = new String[1];
        // Note: when(pg.approve(any()))는 앞선 스텁의 답을 null 인자로 먼저 실행해 NPE가 나므로 doAnswer로 다시 정한다
        doAnswer(invocation -> {
            stored[0] = jdbc.queryForObject("SELECT pg_tx_id FROM payments WHERE reservation_id = ?", String.class, reservation);
            return new PgClient.Unknown(UnknownCause.TIMEOUT);
        }).when(pg).approve(any());

        var response = approve(USER, key(), token(USER, reservation, AMOUNT).build(), "tgen_before_call");

        assertEquals(202, response.status());
        assertEquals("tgen_before_call", stored[0], "TX1이 paymentKey를 PG 호출 전에 저장해야 한다");
        assertEquals("tgen_before_call", jdbc.queryForObject("SELECT pg_tx_id FROM payments WHERE reservation_id = ?",
                String.class, reservation), "응답을 잃어도 같은 식별자가 남는다");
        var captured = ArgumentCaptor.forClass(ApproveCommand.class);
        verify(pg).approve(captured.capture());
        assertEquals(new PgPaymentRef("tgen_before_call"), captured.getValue().clientPaymentRef());
    }

    @Test
    void 토스_모드에서_clientPaymentRef가_없으면_400이고_PG를_부르지_않고_행도_남기지_않는다() throws Exception {
        var reservation = newReservation();
        var key = key();

        var response = approve(USER, key, token(USER, reservation, AMOUNT).build(), null);

        assertEquals(400, response.status());
        assertEquals("VALIDATION_FAILED", response.json().get("code").asString());
        verify(pg, never()).approve(any());
        assertEquals(0, paymentCount(reservation));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE idem_key = ?::uuid",
                Integer.class, key));
    }

    @Test
    void 공백이나_200자를_넘는_clientPaymentRef는_400이다() throws Exception {
        var token = token(USER, newReservation(), AMOUNT).build();

        assertEquals(400, approve(USER, key(), token, "has space").status());
        assertEquals(400, approve(USER, key(), token, "a".repeat(201)).status());
        assertEquals(400, approve(USER, key(), token, "").status());
        verify(pg, never()).approve(any());
    }

    @Test
    void 같은_멱등키에_다른_paymentKey를_보내면_409_IDEMPOTENCY_KEY_REUSED다() throws Exception {
        var reservation = newReservation();
        var token = token(USER, reservation, AMOUNT).build();
        var key = key();
        assertEquals(200, approve(USER, key, token, "tgen_first").status());

        var response = approve(USER, key, token, "tgen_second");

        assertEquals(409, response.status());
        assertEquals("IDEMPOTENCY_KEY_REUSED", response.json().get("code").asString());
        assertEquals(1, paymentCount(reservation));
    }

    @Test
    void 같은_멱등키에_같은_paymentKey로_다시_보내면_저장된_응답을_재생하고_PG를_다시_부르지_않는다() throws Exception {
        var token = token(USER, newReservation(), AMOUNT).build();
        var key = key();
        var first = approve(USER, key, token, "tgen_replay");

        var again = approve(USER, key, token, "tgen_replay");

        assertEquals(200, first.status());
        assertEquals(200, again.status());
        verify(pg, times(1)).approve(any());
    }

    @Test
    void 다른_결제가_이미_쓰는_paymentKey를_보내면_503이_아니라_409_PAYMENT_ALREADY_EXISTS다() throws Exception {
        assertEquals(200, approve(USER, key(), token(USER, newReservation(), AMOUNT).build(), "tgen_reused").status());
        var otherReservation = newReservation();
        var otherKey = key();

        var response = approve(USER, otherKey, token(USER, otherReservation, AMOUNT).build(), "tgen_reused");

        assertEquals(409, response.status());
        assertEquals("PAYMENT_ALREADY_EXISTS", response.json().get("code").asString());
        assertEquals(0, paymentCount(otherReservation));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE idem_key = ?::uuid",
                Integer.class, otherKey));
        verify(pg, times(1)).approve(any());
    }

    @Test
    void 승인_응답의_paymentKey가_같아서_TX2가_pg_tx_id를_같은_값으로_기록한다() throws Exception {
        var reservation = newReservation();

        var response = approve(USER, key(), token(USER, reservation, AMOUNT).build(), "tgen_approved");

        assertEquals(200, response.status());
        assertEquals("tgen_approved", jdbc.queryForObject("SELECT pg_tx_id FROM payments WHERE reservation_id = ?",
                String.class, reservation));
        assertEquals("APPROVED", jdbc.queryForObject("SELECT status FROM payments WHERE reservation_id = ?",
                String.class, reservation));
    }

    @Test
    void checkout은_다른_사용자의_토큰과_만료된_토큰과_깨진_토큰을_403으로_거절한다() throws Exception {
        var reservation = newReservation();

        assertEquals(403, checkout(USER + 1, token(USER, reservation, AMOUNT).build()).status());
        assertEquals(403, checkout(USER, token(USER, reservation, AMOUNT)
                .expiresAt(Instant.now().minusSeconds(5)).build()).status());
        assertEquals(403, checkout(USER, "not-a-token").status());
        assertEquals(403, checkout(USER, "a".repeat(5000)).status());
    }

    @Test
    void checkout은_인증_없이는_401이다() throws Exception {
        var response = post("/api/v1/payments/checkout", null,
                "{\"paymentToken\":\"" + token(USER, newReservation(), AMOUNT).build() + "\"}", false);

        assertEquals(401, response.status());
    }

    private long newReservation() {
        return RESERVATION_IDS.incrementAndGet();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private int paymentCount(long reservationId) {
        return jdbc.queryForObject("SELECT count(*) FROM payments WHERE reservation_id = ?", Integer.class, reservationId);
    }

    // PG는 결제창에서 받은 paymentKey 그대로 승인한다
    private static PgClient.Approved approved(ApproveCommand command) {
        return new PgClient.Approved(command.clientPaymentRef(), BigDecimal.valueOf(AMOUNT), "KRW", Instant.now());
    }

    private Response approve(long userId, String key, String token, String clientPaymentRef) throws Exception {
        var ref = clientPaymentRef == null ? "" : ",\"clientPaymentRef\":\"" + clientPaymentRef + "\"";
        return post("/api/v1/payments", userId, key,
                "{\"paymentToken\":\"" + token + "\",\"amount\":" + AMOUNT + ",\"method\":\"CARD\"" + ref + "}", true);
    }

    private Response checkout(long userId, String token) throws Exception {
        return post("/api/v1/payments/checkout", userId, null, "{\"paymentToken\":\"" + token + "\"}", true);
    }

    private Response post(String path, String key, String body, boolean authenticated) throws Exception {
        return post(path, USER, key, body, authenticated);
    }

    private Response post(String path, long userId, String key, String body, boolean authenticated) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) request.header("Idempotency-Key", key);
        if (authenticated) request.header("X-User-Id", Long.toString(userId)).header("X-User-Role", "USER");
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private record Response(int status, String body) {
        JsonNode json() {
            return JsonMapper.builder().build().readTree(body);
        }
    }
}
