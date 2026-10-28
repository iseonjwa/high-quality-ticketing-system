package com.iseonjwa.payment.payment.presentation;

import com.iseonjwa.payment.payment.application.PaymentTx;
import com.iseonjwa.payment.payment.domain.IdempotencyRecordRepository;
import com.iseonjwa.payment.payment.domain.PaymentRepository;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgPaymentRef;
import com.iseonjwa.payment.payment.domain.DeclineReason;
import com.iseonjwa.payment.pg.application.UnknownCause;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.iseonjwa.payment.support.PaymentTokenFixtures.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 결제 승인 API를 실제 HTTP로 호출해 확인한다(E2, E6, N1, N3, N6, N14). PG는 PgClient 포트를 대체해 결과를 정한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class PaymentApprovalApiTest {
    private static final long USER = 42L;
    private static final long AMOUNT = 50000L;
    private static final AtomicLong RESERVATION_IDS = new AtomicLong(8_100_000);
    private static final long FIRST_RESERVATION = 8_100_000;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @MockitoBean
    PgClient pg;
    @MockitoSpyBean
    PaymentTx paymentTx;
    @MockitoSpyBean
    IdempotencyRecordRepository idempotency;
    @MockitoSpyBean
    PaymentRepository payments;

    @BeforeEach
    void stubPg() {
        reset(pg, paymentTx, idempotency, payments);
        when(pg.approve(any())).thenAnswer(invocation -> approved(AMOUNT));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM idempotency_record WHERE resource_id IN (SELECT id FROM payment WHERE reservation_id >= ?)",
                FIRST_RESERVATION);
        jdbc.update("DELETE FROM outbox WHERE message_key ~ '^81[0-9]{5}$'");
        jdbc.update("DELETE FROM payment_status_history WHERE payment_id IN (SELECT id FROM payment WHERE reservation_id >= ?)",
                FIRST_RESERVATION);
        jdbc.update("DELETE FROM payment WHERE reservation_id >= ?", FIRST_RESERVATION);
    }

    @Test
    void 승인하면_200_APPROVED와_결과_이벤트와_저장된_응답을_남긴다() throws Exception {
        var reservation = newReservation();

        var response = approve(USER, key(), token(USER, reservation, AMOUNT).build(), AMOUNT);

        assertEquals(200, response.status());
        var data = response.json().get("data");
        assertEquals("APPROVED", data.get("status").asString());
        assertEquals(reservation, data.get("reservationId").asLong());
        assertEquals(AMOUNT, data.get("amount").asLong());
        assertNotNull(data.get("approvedAt"));
        assertEquals("APPROVED", jdbc.queryForObject("SELECT status FROM payment WHERE reservation_id = ?", String.class, reservation));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox WHERE message_key = ? AND event_type = 'PaymentApproved'",
                Integer.class, String.valueOf(reservation)));
        assertEquals(200, jdbc.queryForObject("SELECT response_status FROM idempotency_record WHERE resource_id = "
                + "(SELECT id FROM payment WHERE reservation_id = ?)", Integer.class, reservation));
    }

    @Test
    void PG가_거절하면_200_DECLINED와_사유를_돌려준다() throws Exception {
        var reservation = newReservation();
        when(pg.approve(any())).thenReturn(new PgClient.Declined(DeclineReason.CARD_DECLINED));

        var response = approve(USER, key(), token(USER, reservation, AMOUNT).build(), AMOUNT);

        assertEquals(200, response.status());
        assertEquals("DECLINED", response.json().get("data").get("status").asString());
        assertEquals("CARD_DECLINED", response.json().get("data").get("reasonCode").asString());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox WHERE message_key = ? AND event_type = 'PaymentDeclined'",
                Integer.class, String.valueOf(reservation)));
    }

    @Test
    void 같은_키로_다시_요청하면_저장된_응답을_재생하고_PG는_한_번만_부른다() throws Exception {
        var reservation = newReservation();
        var key = key();
        var token = token(USER, reservation, AMOUNT).build();
        var first = approve(USER, key, token, AMOUNT);

        var second = approve(USER, key, token, AMOUNT);

        assertEquals(200, second.status());
        assertEquals(first.json().get("data").get("paymentId").asLong(), second.json().get("data").get("paymentId").asLong());
        verify(pg, times(1)).approve(any());
        assertEquals(1, paymentCount(reservation));
    }

    @Test
    void 새_키로_같은_예매를_요청하면_기존_결제로_응답하고_새_키를_ALIAS로_기록한다() throws Exception {
        var reservation = newReservation();
        var token = token(USER, reservation, AMOUNT).build();
        var first = approve(USER, key(), token, AMOUNT);
        var newKey = key();

        var second = approve(USER, newKey, token, AMOUNT);
        var third = approve(USER, newKey, token, AMOUNT);

        assertEquals(200, second.status());
        assertEquals(first.json().get("data").get("paymentId").asLong(), second.json().get("data").get("paymentId").asLong());
        assertEquals(200, third.status());
        assertEquals("ALIAS", jdbc.queryForObject("SELECT link_type FROM idempotency_record WHERE idem_key = ?::uuid",
                String.class, newKey));
        verify(pg, times(1)).approve(any());
        assertEquals(1, paymentCount(reservation));
    }

    @Test
    void 거절된_예매를_같은_토큰으로_다시_승인하면_409_PAYMENT_ALREADY_CLOSED이고_PG를_부르지_않는다() throws Exception {
        var reservation = newReservation();
        var token = token(USER, reservation, AMOUNT).build();
        when(pg.approve(any())).thenReturn(new PgClient.Declined(DeclineReason.OTHER));
        approve(USER, key(), token, AMOUNT);

        var retry = approve(USER, key(), token, AMOUNT);

        assertEquals(409, retry.status());
        assertEquals("PAYMENT_ALREADY_CLOSED", retry.json().get("code").asString());
        assertNull(retry.location());
        verify(pg, times(1)).approve(any());
        assertEquals(1, paymentCount(reservation));
    }

    @Test
    void PG_결과를_모르면_202와_Location을_돌려주고_같은_키_재요청도_같은_결제로_202다() throws Exception {
        var reservation = newReservation();
        when(pg.approve(any())).thenReturn(new PgClient.Unknown(UnknownCause.TIMEOUT));
        var key = key();
        var token = token(USER, reservation, AMOUNT).build();

        var first = approve(USER, key, token, AMOUNT);
        var retry = approve(USER, key, token, AMOUNT);

        assertEquals(202, first.status());
        assertEquals("UNKNOWN", first.json().get("data").get("status").asString());
        assertEquals(10, first.json().get("data").get("nextPollAfterSeconds").asInt());
        var paymentId = first.json().get("data").get("paymentId").asLong();
        assertEquals("/api/v1/payments/" + paymentId, first.location());
        assertEquals(202, retry.status());
        assertEquals(paymentId, retry.json().get("data").get("paymentId").asLong());
        assertEquals("UNKNOWN", jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, paymentId));
        assertNotNull(jdbc.queryForObject("SELECT next_check_at FROM payment WHERE id = ?", Instant.class, paymentId),
                "재확인 스케줄러가 집을 수 있어야 한다");
        verify(pg, times(1)).approve(any());
    }

    @Test
    void 확인_중인_결제에_새_키로_요청하면_같은_결제로_202다() throws Exception {
        var reservation = newReservation();
        when(pg.approve(any())).thenReturn(new PgClient.Unknown(UnknownCause.TIMEOUT));
        var token = token(USER, reservation, AMOUNT).build();
        var first = approve(USER, key(), token, AMOUNT);

        var viaNewKey = approve(USER, key(), token, AMOUNT);

        assertEquals(202, viaNewKey.status());
        assertEquals(first.json().get("data").get("paymentId").asLong(), viaNewKey.json().get("data").get("paymentId").asLong());
        verify(pg, times(1)).approve(any());
    }

    @Test
    void 다른_사용자가_같은_예매를_요청하면_409이고_Location과_기존_결제_정보를_주지_않는다() throws Exception {
        var reservation = newReservation();
        approve(USER, key(), token(USER, reservation, AMOUNT).build(), AMOUNT);

        var other = approve(USER + 1, key(), token(USER + 1, reservation, AMOUNT).build(), AMOUNT);

        assertEquals(409, other.status());
        assertEquals("PAYMENT_ALREADY_EXISTS", other.json().get("code").asString());
        assertNull(other.location());
        assertFalse(other.body().contains("paymentId"));
        assertEquals(1, paymentCount(reservation));
    }

    @Test
    void 같은_키에_다른_본문이면_409_IDEMPOTENCY_KEY_REUSED다() throws Exception {
        var key = key();
        approve(USER, key, token(USER, newReservation(), AMOUNT).build(), AMOUNT);

        var reused = approve(USER, key, token(USER, newReservation(), AMOUNT).build(), AMOUNT);

        assertEquals(409, reused.status());
        assertEquals("IDEMPOTENCY_KEY_REUSED", reused.json().get("code").asString());
    }

    @Test
    void 요청_금액이_토큰_금액과_다르면_400이고_결제도_PG_호출도_없다() throws Exception {
        var reservation = newReservation();

        var response = approve(USER, key(), token(USER, reservation, AMOUNT).build(), AMOUNT - 1000);

        assertEquals(400, response.status());
        assertEquals("PAYMENT_AMOUNT_MISMATCH", response.json().get("code").asString());
        assertEquals(0, paymentCount(reservation));
        verify(pg, never()).approve(any());
    }

    @Test
    void 위조되거나_다른_사용자의_토큰은_403이다() throws Exception {
        var reservation = newReservation();
        var forged = token(USER, reservation, AMOUNT).secret("ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100").build();

        assertEquals(403, approve(USER, key(), forged, AMOUNT).status());
        assertEquals(403, approve(USER, key(), token(USER + 1, reservation, AMOUNT).build(), AMOUNT).status(),
                "토큰의 사용자가 인증된 사용자와 달라야 거절한다");
        assertEquals(0, paymentCount(reservation));
    }

    @Test
    void 만료된_토큰으로는_기존_결제가_없으면_신규_승인을_하지_않고_403이다() throws Exception {
        var reservation = newReservation();
        var expired = token(USER, reservation, AMOUNT).expiresAt(Instant.now().minusSeconds(30)).build();

        var response = approve(USER, key(), expired, AMOUNT);

        assertEquals(403, response.status());
        assertEquals("PAYMENT_TOKEN_INVALID", response.json().get("code").asString());
        assertEquals(0, paymentCount(reservation));
        verify(pg, never()).approve(any());
    }

    @Test
    void 토큰이_만료돼도_같은_키_재요청은_저장된_결과를_받는다() throws Exception {
        var reservation = newReservation();
        var key = key();
        var soon = token(USER, reservation, AMOUNT).expiresAt(Instant.now().plusSeconds(3)).build();
        assertEquals(200, approve(USER, key, soon, AMOUNT).status());
        Thread.sleep(3300);

        var retry = approve(USER, key, soon, AMOUNT);

        assertEquals(200, retry.status());
        assertEquals("APPROVED", retry.json().get("data").get("status").asString());
    }

    @Test
    void 만료된_토큰이라도_기존_결제를_새_키로_조회할_수_있다() throws Exception {
        var reservation = newReservation();
        var soon = token(USER, reservation, AMOUNT).expiresAt(Instant.now().plusSeconds(3)).build();
        assertEquals(200, approve(USER, key(), soon, AMOUNT).status());
        Thread.sleep(3300);

        var viaNewKey = approve(USER, key(), soon, AMOUNT);

        assertEquals(200, viaNewKey.status());
        verify(pg, times(1)).approve(any());
    }

    @Test
    void PG가_다른_금액을_승인하면_확인_필요로_두고_예매를_확정하는_이벤트를_내지_않는다() throws Exception {
        var reservation = newReservation();
        when(pg.approve(any())).thenAnswer(invocation -> approved(AMOUNT - 1000));

        var response = approve(USER, key(), token(USER, reservation, AMOUNT).build(), AMOUNT);

        assertEquals(409, response.status());
        assertEquals("PAYMENT_ALREADY_EXISTS", response.json().get("code").asString());
        assertNotNull(response.location());
        assertEquals("NEEDS_MANUAL_CHECK", jdbc.queryForObject("SELECT status FROM payment WHERE reservation_id = ?",
                String.class, reservation));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox WHERE message_key = ?", Integer.class,
                String.valueOf(reservation)));
    }

    @Test
    void TX2가_전이_뒤에_실패하면_전부_롤백되고_202로_응답하며_재확인_대상으로_남는다() throws Exception {
        var reservation = newReservation();
        doThrow(new DataAccessResourceFailureException("DB 장애")).when(idempotency)
                .saveResponse(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());

        var response = approve(USER, key(), token(USER, reservation, AMOUNT).build(), AMOUNT);

        assertEquals(202, response.status());
        assertEquals("REQUESTED", response.json().get("data").get("status").asString());
        assertNotNull(response.location());
        assertEquals("REQUESTED", jdbc.queryForObject("SELECT status FROM payment WHERE reservation_id = ?",
                String.class, reservation), "승인 전이가 롤백돼 REQUESTED로 남는다");
        assertNotNull(jdbc.queryForObject("SELECT next_check_at FROM payment WHERE reservation_id = ?", Instant.class,
                reservation));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox WHERE message_key = ?", Integer.class,
                String.valueOf(reservation)), "이벤트도 롤백된다");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM payment_status_history WHERE payment_id = "
                + "(SELECT id FROM payment WHERE reservation_id = ?)", Integer.class, reservation));
        assertNull(jdbc.queryForObject("SELECT response_status FROM idempotency_record WHERE resource_id = "
                + "(SELECT id FROM payment WHERE reservation_id = ?)", Integer.class, reservation));
    }

    @Test
    void PG_호출_전_구간의_DB_오류는_500이_아니라_503이다() throws Exception {
        doThrow(new DataAccessResourceFailureException("연결 대기 초과")).when(payments).findByReservationId(any());

        var response = approve(USER, key(), token(USER, newReservation(), AMOUNT).build(), AMOUNT);

        assertEquals(503, response.status());
        assertEquals("SERVICE_UNAVAILABLE", response.json().get("code").asString());
        verify(pg, never()).approve(any());
    }

    @Test
    void PG_어댑터가_예외를_던져도_결과를_모르는_것으로_다뤄_거절로_닫지_않는다() throws Exception {
        var reservation = newReservation();
        when(pg.approve(any())).thenThrow(new IllegalStateException("어댑터 오류"));

        var response = approve(USER, key(), token(USER, reservation, AMOUNT).build(), AMOUNT);

        assertEquals(202, response.status());
        assertEquals("UNKNOWN", jdbc.queryForObject("SELECT status FROM payment WHERE reservation_id = ?",
                String.class, reservation));
    }

    @Test
    void TX1과_PG_호출_사이에_토큰이_만료되면_PG를_부르지_않고_거절한다() throws Exception {
        var reservation = newReservation();
        // TX1 직후 토큰이 만료될 때까지 기다리게 한다. JWT exp는 초 단위라 여유를 둔다
        doAnswer(invocation -> {
            var created = invocation.callRealMethod();
            Thread.sleep(3200);
            return created;
        }).when(paymentTx).createRequested(any(), any(), any());
        var token = token(USER, reservation, AMOUNT).expiresAt(Instant.now().plusSeconds(3)).build();

        var response = approve(USER, key(), token, AMOUNT);

        assertEquals(200, response.status());
        assertEquals("DECLINED", response.json().get("data").get("status").asString());
        assertEquals("TOKEN_EXPIRED", response.json().get("data").get("reasonCode").asString());
        verify(pg, never()).approve(any());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox WHERE message_key = ? AND event_type = 'PaymentDeclined'",
                Integer.class, String.valueOf(reservation)));
    }

    @Test
    void 같은_키가_동시에_와도_결제는_한_건이고_PG는_한_번만_부른다() throws Exception {
        var reservation = newReservation();
        var key = key();
        var token = token(USER, reservation, AMOUNT).build();
        var responses = concurrently(() -> approve(USER, key, token, AMOUNT), () -> approve(USER, key, token, AMOUNT));

        // 두 요청이 모두 TX1까지 들어온 뒤 진행해 유니크 위반 경로를 실제로 탄다
        verify(paymentTx, times(2)).createRequested(any(), any(), any());
        assertEquals(1, paymentCount(reservation));
        verify(pg, times(1)).approve(any());
        for (var response : responses) {
            assertTrue(response.status() == 200 || response.status() == 202, "동시 요청도 500이 아니다: " + response.status());
        }
        assertEquals(responses.get(0).json().get("data").get("paymentId").asLong(),
                responses.get(1).json().get("data").get("paymentId").asLong());
    }

    @Test
    void 다른_키가_같은_예매에_동시에_와도_결제는_한_건이고_진_쪽은_ALIAS로_같은_결제를_받는다() throws Exception {
        var reservation = newReservation();
        var token = token(USER, reservation, AMOUNT).build();
        var keyA = key();
        var keyB = key();
        var responses = concurrently(() -> approve(USER, keyA, token, AMOUNT), () -> approve(USER, keyB, token, AMOUNT));

        verify(paymentTx, times(2)).createRequested(any(), any(), any());
        assertEquals(1, paymentCount(reservation));
        verify(pg, times(1)).approve(any());
        assertEquals(responses.get(0).json().get("data").get("paymentId").asLong(),
                responses.get(1).json().get("data").get("paymentId").asLong());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM idempotency_record WHERE link_type = 'ALIAS' "
                + "AND resource_id = (SELECT id FROM payment WHERE reservation_id = ?)", Integer.class, reservation));
    }

    @Test
    void 같은_사용자가_같은_키로_다른_예매를_동시에_보내면_한쪽은_409이고_결제는_한_건이다() throws Exception {
        var key = key();
        var reservationA = newReservation();
        var reservationB = newReservation();
        var tokenA = token(USER, reservationA, AMOUNT).build();
        var tokenB = token(USER, reservationB, AMOUNT).build();

        var responses = concurrently(() -> approve(USER, key, tokenA, AMOUNT), () -> approve(USER, key, tokenB, AMOUNT));

        var statuses = responses.stream().map(Response::status).sorted().toList();
        assertTrue(statuses.contains(409), "다른 본문이 같은 키를 쓴 요청은 거절된다: " + statuses);
        assertEquals(1, paymentCount(reservationA) + paymentCount(reservationB), "승인되는 결제는 하나뿐이다");
        verify(pg, times(1)).approve(any());
    }

    // 두 요청이 모두 TX1(createRequested)에 들어온 뒤에 진행하도록 막아 두고 동시에 보낸다
    @SafeVarargs
    private List<Response> concurrently(Callable<Response>... requests) throws Exception {
        var barrier = new CyclicBarrier(requests.length);
        doAnswer(invocation -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException | java.util.concurrent.BrokenBarrierException exception) {
                // 한 요청이 TX1에 도달하지 않는 경로(재생 등)면 기다리지 않고 진행한다
            }
            return invocation.callRealMethod();
        }).when(paymentTx).createRequested(any(), any(), any());
        when(pg.approve(any())).thenAnswer(invocation -> {
            Thread.sleep(300);
            return approved(AMOUNT);
        });
        var executor = Executors.newFixedThreadPool(requests.length);
        try {
            var futures = new ArrayList<Future<Response>>();
            for (var request : requests) futures.add(executor.submit(request));
            var results = new ArrayList<Response>();
            for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 요청_금액에_소수가_있거나_토큰이_너무_길면_거부한다() throws Exception {
        var token = token(USER, newReservation(), AMOUNT).build();

        var decimal = post(USER, key(), "{\"paymentToken\":\"" + token + "\",\"amount\":50000.5,\"method\":\"CARD\"}", true);
        var tooLong = post(USER, key(), "{\"paymentToken\":\"" + "a".repeat(4097) + "\",\"amount\":50000,\"method\":\"CARD\"}", true);

        assertEquals(400, decimal.status());
        assertEquals("VALIDATION_FAILED", decimal.json().get("code").asString());
        assertEquals(403, tooLong.status());
        assertEquals("PAYMENT_TOKEN_INVALID", tooLong.json().get("code").asString());
        verify(pg, never()).approve(any());
    }

    @Test
    void 같은_키라도_사용자가_다르면_서로_독립이다() throws Exception {
        var key = key();
        var first = approve(USER, key, token(USER, newReservation(), AMOUNT).build(), AMOUNT);
        var other = approve(USER + 1, key, token(USER + 1, newReservation(), AMOUNT).build(), AMOUNT);

        assertEquals(200, first.status());
        assertEquals(200, other.status());
        verify(pg, times(2)).approve(any());
    }

    @Test
    void 확인_필요_결제에_새_키로_요청하면_409와_Location이다() throws Exception {
        var reservation = newReservation();
        when(pg.approve(any())).thenAnswer(invocation -> approved(AMOUNT - 1000));
        var token = token(USER, reservation, AMOUNT).build();
        approve(USER, key(), token, AMOUNT);

        var viaNewKey = approve(USER, key(), token, AMOUNT);

        assertEquals(409, viaNewKey.status());
        assertEquals("PAYMENT_ALREADY_EXISTS", viaNewKey.json().get("code").asString());
        assertNotNull(viaNewKey.location());
    }

    @Test
    void CARD가_아닌_수단이나_잘못된_요청_형식은_400이다() throws Exception {
        var token = token(USER, newReservation(), AMOUNT).build();

        var billing = post(USER, key(), "{\"paymentToken\":\"" + token + "\",\"amount\":50000,\"method\":\"BILLING\"}", true);
        assertEquals(400, billing.status());
        assertEquals("VALIDATION_FAILED", billing.json().get("code").asString());
        assertEquals(400, post(USER, key(), "{\"paymentToken\":\"" + token + "\",\"amount\":-1,\"method\":\"CARD\"}", true).status());
        assertEquals(400, post(USER, "not-a-uuid", "{\"paymentToken\":\"" + token + "\",\"amount\":50000,\"method\":\"CARD\"}", true).status());
        assertEquals(400, post(USER, null, "{\"paymentToken\":\"" + token + "\",\"amount\":50000,\"method\":\"CARD\"}", true).status());
        verify(pg, never()).approve(any());
    }

    @Test
    void 인증_헤더가_없으면_401이다() throws Exception {
        var token = token(USER, newReservation(), AMOUNT).build();

        var response = post(USER, key(), "{\"paymentToken\":\"" + token + "\",\"amount\":50000,\"method\":\"CARD\"}", false);

        assertEquals(401, response.status());
        verify(pg, never()).approve(any());
    }

    private long newReservation() {
        return RESERVATION_IDS.incrementAndGet();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private int paymentCount(long reservationId) {
        return jdbc.queryForObject("SELECT count(*) FROM payment WHERE reservation_id = ?", Integer.class, reservationId);
    }

    private static PgClient.Approved approved(long amount) {
        return new PgClient.Approved(new PgPaymentRef("pg-" + UUID.randomUUID()), BigDecimal.valueOf(amount), "KRW",
                Instant.now());
    }

    private Response approve(long userId, String key, String token, long amount) throws Exception {
        return post(userId, key, "{\"paymentToken\":\"" + token + "\",\"amount\":" + amount + ",\"method\":\"CARD\"}", true);
    }

    private Response post(long userId, String key, String body, boolean authenticated) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/payments"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) request.header("Idempotency-Key", key);
        if (authenticated) request.header("X-User-Id", Long.toString(userId)).header("X-User-Role", "USER");
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body(), response.headers().firstValue("Location").orElse(null));
    }

    private record Response(int status, String body, String location) {
        JsonNode json() {
            return JsonMapper.builder().build().readTree(body);
        }
    }
}
