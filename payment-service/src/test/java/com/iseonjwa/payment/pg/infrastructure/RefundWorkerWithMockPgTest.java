package com.iseonjwa.payment.pg.infrastructure;

import com.iseonjwa.payment.pg.application.ApproveCommand;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgIdempotencyKey;
import com.iseonjwa.payment.pg.application.PgOrderId;
import com.iseonjwa.payment.pg.application.PgPaymentRef;
import com.iseonjwa.payment.pg.application.PgRefundId;
import com.iseonjwa.payment.pg.application.RefundCommand;
import com.iseonjwa.payment.refund.application.RefundRequestService;
import com.iseonjwa.payment.refund.application.RefundRequestedPayload;
import com.iseonjwa.payment.refund.application.RefundWorkerService;
import com.iseonjwa.payment.support.RefundWorkerFixtures;
import com.iseonjwa.payment.support.RefundWorkerFixtures.Created;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 환불 워커를 실제 mock-pg(별도 프로세스)로 확인한다(events-and-reliability.md 6절, E8·E13). 결제는 mock-pg가 실제로 승인한
 * 거래({@code pg_tx_id})를 가진 APPROVED 행이고, 환불은 {@code RefundRequested} 처리로 만든다. 워커는 서비스 메서드를 직접 부른다.
 */
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class RefundWorkerWithMockPgTest {
    private static final long AMOUNT = RefundWorkerFixtures.AMOUNT;

    // 스프링 컨텍스트가 만들어지기 전에 mock-pg가 떠 있어야 base-url을 넘길 수 있다
    private static final MockPgProcess SERVER = MockPgProcess.start();

    @DynamicPropertySource
    static void mockPgUrl(DynamicPropertyRegistry registry) {
        registry.add("payment.pg.mock.base-url", SERVER::baseUrl);
    }

    @Autowired
    RefundWorkerService worker;
    @Autowired
    RefundRequestService refundRequests;
    @MockitoSpyBean
    PgClient pg;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper mapper;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<UUID> eventIds = new ArrayList<>();
    private RefundWorkerFixtures fixtures;

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    @BeforeEach
    void setUp() {
        fixtures = RefundWorkerFixtures.forMockPg(jdbc);
        fixtures.cleanUp();
        fixtures.parkAllRefunds();
    }

    @AfterEach
    void tearDown() {
        for (var eventId : eventIds) {
            jdbc.update("DELETE FROM processed_events WHERE event_id = ?", eventId);
        }
        fixtures.cleanUp();
    }

    @Test
    void E8_선점이_만료된_뒤_승인이_도착하면_환불이_실제_PG에서_완료되고_결제가_REFUNDED가_된다() throws Exception {
        var payment = approvedPayment();
        requestRefund(payment, "EXPIRED_AFTER_APPROVAL");
        var refund = fixtures.withRefund(payment);
        fixtures.parkAllRefundsExcept(refund.refundId());

        assertTrue(worker.processNext());

        assertEquals("REFUNDED", fixtures.refundStatus(refund));
        assertEquals("REFUNDED", fixtures.paymentStatus(refund));
        assertEquals(List.of("PaymentRefunded"), fixtures.outboxEventTypes(refund));
        var body = mapper.readTree(fixtures.outboxPayload(refund)).get("payload");
        assertEquals(refund.paymentId(), body.get("paymentId").asLong());
        assertEquals(refund.reservationId(), body.get("reservationId").asLong());
        assertEquals(42L, body.get("userId").asLong());
        assertEquals(AMOUNT, body.get("amount").asLong());
        assertEquals("KRW", body.get("currency").asString());
        assertTrue(Instant.parse(body.get("refundedAt").asString()).isBefore(Instant.now().plusSeconds(5)));
        var pgRefund = get("/mock-pg/refunds/" + refund.pgRefundId());
        assertEquals(200, pgRefund.statusCode(), pgRefund.body());
        assertEquals("REFUNDED", mapper.readTree(pgRefund.body()).get("status").asString());
        assertEquals(1, fixtures.historyCount(refund, "REFUND") - 1, "생성 이력 하나와 완료 이력 하나");
    }

    @Test
    void E13_서로_다른_eventId의_RefundRequested_둘은_환불_한_건으로_합쳐지고_PG_환불은_한_번만_부른다() throws Exception {
        var payment = approvedPayment();
        requestRefund(payment, "EXPIRED_AFTER_APPROVAL");
        requestRefund(payment, "CANCELED");
        assertEquals(1, fixtures.refundCount(payment), "결제당 환불은 한 건이다");
        var refund = fixtures.withRefund(payment);
        fixtures.parkAllRefundsExcept(refund.refundId());
        clearInvocations(pg);

        assertTrue(worker.processNext());
        assertFalse(worker.processNext(), "두 번째 요청이 환불을 한 번 더 만들지 않았다");

        verify(pg, times(1)).refund(any());
        assertEquals("REFUNDED", fixtures.refundStatus(refund));
        assertEquals(1, fixtures.outboxCount(refund), "PaymentRefunded는 한 건이다");
        // Note: mock-pg의 환불 호출 계수(원장 조회)는 ISE-128 이후에 생기므로, 호출 횟수는 PgClient 스파이로 센다
        assertEquals("REFUNDED", mapper.readTree(get("/mock-pg/refunds/" + refund.pgRefundId()).body()).get("status").asString());
    }

    @Test
    void 응답이_유실돼_이미_PG에_환불이_있으면_findRefund로_확정하고_refund는_다시_부르지_않는다() {
        var payment = approvedPayment();
        requestRefund(payment, "CANCELED");
        var refund = fixtures.withRefund(payment);
        fixtures.parkAllRefundsExcept(refund.refundId());
        // 워커가 보낸 요청은 PG에서 처리됐지만 응답이 유실된 상황: 어댑터로 PG에 먼저 환불을 넣고 요청 표시를 채운다
        var lost = pg.refund(refundCommand(refund));
        assertInstanceOf(PgClient.Refunded.class, lost);
        jdbc.update("UPDATE refunds SET pg_requested_at = now() - interval '1 minute', attempt = 1 WHERE id = ?",
                refund.refundId());
        clearInvocations(pg);

        assertTrue(worker.processNext());

        verify(pg, times(1)).findRefund(new PgRefundId(refund.pgRefundId()), new PgPaymentRef(refund.pgTxId()));
        verify(pg, never()).refund(any());
        assertEquals("REFUNDED", fixtures.refundStatus(refund));
        assertEquals("REFUNDED", fixtures.paymentStatus(refund));
        assertEquals(1, fixtures.outboxCount(refund));
    }

    @Test
    void PG에_환불이_없으면_조회가_NotFound를_주고_같은_pg_refund_id로_refund를_한_번_다시_부른다() throws Exception {
        var payment = approvedPayment();
        requestRefund(payment, "CANCELED");
        var refund = fixtures.withRefund(payment);
        fixtures.parkAllRefundsExcept(refund.refundId());
        jdbc.update("UPDATE refunds SET pg_requested_at = now() - interval '1 minute', attempt = 1 WHERE id = ?",
                refund.refundId());
        assertEquals(404, get("/mock-pg/refunds/" + refund.pgRefundId()).statusCode(), "PG에는 아직 환불이 없다");
        clearInvocations(pg);

        assertTrue(worker.processNext());

        verify(pg, times(1)).findRefund(any(), any());
        var captor = org.mockito.ArgumentCaptor.forClass(RefundCommand.class);
        verify(pg, times(1)).refund(captor.capture());
        assertEquals(new PgRefundId(refund.pgRefundId()), captor.getValue().refundId());
        assertEquals("REFUNDED", fixtures.refundStatus(refund));
        assertEquals("REFUNDED", fixtures.paymentStatus(refund));
        assertEquals("REFUNDED", mapper.readTree(get("/mock-pg/refunds/" + refund.pgRefundId()).body()).get("status").asString());
    }

    // mock-pg가 실제로 승인한 거래를 가진 APPROVED 결제를 만든다
    private Created approvedPayment() {
        var orderId = UUID.randomUUID();
        var order = new PgOrderId(orderId);
        var approved = assertInstanceOf(PgClient.Approved.class, pg.approve(new ApproveCommand(order,
                BigDecimal.valueOf(AMOUNT), "KRW", PgIdempotencyKey.ofApproval(order), null)));
        return fixtures.payment(RefundWorkerFixtures.spec().pgOrderId(orderId).pgTxId(approved.paymentRef().value()));
    }

    private void requestRefund(Created payment, String reason) {
        var eventId = UUID.randomUUID();
        eventIds.add(eventId);
        refundRequests.handle(eventId, new RefundRequestedPayload(payment.reservationId(), payment.paymentId(), 42L,
                BigDecimal.valueOf(AMOUNT), "KRW", reason));
    }

    private static RefundCommand refundCommand(Created refund) {
        var refundId = new PgRefundId(refund.pgRefundId());
        return new RefundCommand(new PgPaymentRef(refund.pgTxId()), refundId, BigDecimal.valueOf(AMOUNT), "KRW",
                "CANCELED", PgIdempotencyKey.ofRefund(refundId));
    }

    private HttpResponse<String> get(String path) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create(SERVER.baseUrl() + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception exception) {
            throw new IllegalStateException("mock-pg 조회 실패 path=" + path, exception);
        }
    }
}
