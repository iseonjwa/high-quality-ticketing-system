package com.iseonjwa.payment.refund.application;

import com.iseonjwa.common.messaging.consumer.NonRetryableMessageException;
import com.iseonjwa.payment.support.RefundFixtures;
import com.iseonjwa.payment.support.RefundFixtures.CreatedPayment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Note: 처리 이력의 롤백과 동시 처리는 실제로 커밋돼야 확인되므로 테스트 트랜잭션으로 감싸지 않는다.
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class RefundRequestServiceTest {
    @Autowired
    RefundRequestService service;
    @Autowired
    JdbcTemplate jdbc;

    private RefundFixtures fixtures;
    private final List<UUID> eventIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fixtures = new RefundFixtures(jdbc);
        fixtures.cleanUp(List.of());
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanUp(eventIds);
    }

    @Test
    void 승인된_결제면_환불을_REQUESTED로_만들고_이력과_처리_기록을_남긴다() {
        var payment = fixtures.payment("APPROVED");
        var eventId = newEventId();

        service.handle(eventId, payload(payment, p -> p));

        Map<String, Object> refund = jdbc.queryForMap("SELECT * FROM refunds WHERE payment_id = ?", payment.paymentId());
        assertEquals("REQUESTED", refund.get("status"));
        assertEquals(0, BigDecimal.valueOf(RefundFixtures.AMOUNT).compareTo((BigDecimal) refund.get("amount")));
        assertEquals("EVENT", refund.get("source"));
        assertEquals(eventId, refund.get("source_event_id"));
        assertEquals("CANCELED", refund.get("reason"));
        assertNotNull(refund.get("pg_refund_id"));
        var nextCheckAt = jdbc.queryForObject("SELECT next_check_at FROM refunds WHERE payment_id = ?",
                java.time.OffsetDateTime.class, payment.paymentId()).toInstant();
        assertTrue(Math.abs(nextCheckAt.toEpochMilli() - Instant.now().toEpochMilli()) < 10_000, "next_check_at: " + nextCheckAt);

        var history = jdbc.queryForMap("SELECT * FROM payment_status_histories WHERE payment_id = ? AND target_type = 'REFUND'",
                payment.paymentId());
        assertEquals(null, history.get("from_status"));
        assertEquals("REQUESTED", history.get("to_status"));
        assertEquals("CANCELED", history.get("reason"));
        assertEquals("SYSTEM", history.get("actor"));
        assertEquals(1, fixtures.processedCount(eventId));
    }

    @Test
    void 같은_eventId를_두_번_받으면_두_번째는_아무것도_하지_않는다() {
        var payment = fixtures.payment("APPROVED");
        var eventId = newEventId();

        service.handle(eventId, payload(payment, p -> p));
        service.handle(eventId, payload(payment, p -> p));

        assertEquals(1, fixtures.refundCount(payment.paymentId()));
        assertEquals(1, fixtures.refundHistoryCount(payment.paymentId()));
        assertEquals(1, fixtures.processedCount(eventId));
    }

    @Test
    void 다른_eventId_두_개가_같은_결제를_가리키면_환불은_한_건이고_처리_기록은_둘이다() {
        var payment = fixtures.payment("APPROVED");
        var first = newEventId();
        var second = newEventId();

        service.handle(first, payload(payment, p -> p));
        service.handle(second, payload(payment, p -> p));

        assertEquals(1, fixtures.refundCount(payment.paymentId()));
        assertEquals(1, fixtures.refundHistoryCount(payment.paymentId()));
        assertEquals(1, fixtures.processedCount(first));
        assertEquals(1, fixtures.processedCount(second));
        assertEquals(first, jdbc.queryForObject("SELECT source_event_id FROM refunds WHERE payment_id = ?", UUID.class,
                payment.paymentId()));
    }

    @Test
    void 다른_eventId_두_개를_동시에_받아도_환불은_한_건이고_둘_다_예외가_없다() throws Exception {
        var payment = fixtures.payment("APPROVED");
        var first = newEventId();
        var second = newEventId();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var futures = List.of(first, second).stream().map(eventId -> executor.submit(() -> {
                ready.countDown();
                start.await();
                service.handle(eventId, payload(payment, p -> p));
                return null;
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (var future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, fixtures.refundCount(payment.paymentId()));
        assertEquals(1, fixtures.refundHistoryCount(payment.paymentId()));
        assertEquals(1, fixtures.processedCount(first));
        assertEquals(1, fixtures.processedCount(second));
    }

    @Test
    void 이미_환불_행이_있으면_결제가_REFUNDED여도_중복으로_정상_처리한다() {
        var payment = fixtures.payment("APPROVED");
        service.handle(newEventId(), payload(payment, p -> p));
        jdbc.update("UPDATE payments SET status = 'REFUNDED', refunded_at = now() WHERE id = ?", payment.paymentId());
        var duplicate = newEventId();

        service.handle(duplicate, payload(payment, p -> p));

        assertEquals(1, fixtures.refundCount(payment.paymentId()));
        assertEquals(1, fixtures.refundHistoryCount(payment.paymentId()));
        assertEquals(1, fixtures.processedCount(duplicate));
    }

    @Test
    void 결제가_없으면_영구_오류다() {
        var eventId = newEventId();
        var missing = new RefundRequestedPayload(8_899_999L, 999_999_999L, RefundFixtures.USER_ID,
                BigDecimal.valueOf(RefundFixtures.AMOUNT), "KRW", "CANCELED");

        assertThrows(NonRetryableMessageException.class, () -> service.handle(eventId, missing));
        assertEquals(0, fixtures.processedCount(eventId));
    }

    @Test
    void 예매_ID가_다르면_영구_오류다() {
        assertRejected("APPROVED", p -> new RefundRequestedPayload(p.reservationId() + 1, p.paymentId(), p.userId(),
                p.amount(), p.currency(), p.reason()));
    }

    @Test
    void 사용자가_다르면_영구_오류다() {
        assertRejected("APPROVED", p -> new RefundRequestedPayload(p.reservationId(), p.paymentId(), p.userId() + 1,
                p.amount(), p.currency(), p.reason()));
    }

    @Test
    void 금액이_다르면_영구_오류다() {
        assertRejected("APPROVED", p -> new RefundRequestedPayload(p.reservationId(), p.paymentId(), p.userId(),
                p.amount().add(BigDecimal.ONE), p.currency(), p.reason()));
    }

    @Test
    void 통화가_다르면_영구_오류다() {
        assertRejected("APPROVED", p -> new RefundRequestedPayload(p.reservationId(), p.paymentId(), p.userId(),
                p.amount(), "USD", p.reason()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PG_AMOUNT_MISMATCH", "SOMETHING_ELSE", "canceled"})
    void 이벤트로_받을_수_없는_사유면_영구_오류다(String reason) {
        assertRejected("APPROVED", p -> new RefundRequestedPayload(p.reservationId(), p.paymentId(), p.userId(),
                p.amount(), p.currency(), reason));
    }

    @ParameterizedTest
    @ValueSource(strings = {"REQUESTED", "DECLINED"})
    void 승인되지_않은_결제면_영구_오류다(String status) {
        assertRejected(status, p -> p);
    }

    @Test
    void 금액은_소수_표기가_달라도_값이_같으면_통과한다() {
        var payment = fixtures.payment("APPROVED");

        service.handle(newEventId(), payload(payment, p -> new RefundRequestedPayload(p.reservationId(), p.paymentId(),
                p.userId(), new BigDecimal("50000.00"), p.currency(), p.reason())));

        assertEquals(1, fixtures.refundCount(payment.paymentId()));
    }

    private void assertRejected(String paymentStatus, UnaryOperator<RefundRequestedPayload> change) {
        var payment = fixtures.payment(paymentStatus);
        var eventId = newEventId();

        assertThrows(NonRetryableMessageException.class, () -> service.handle(eventId, payload(payment, change)));

        assertEquals(0, fixtures.refundCount(payment.paymentId()));
        assertEquals(0, fixtures.refundHistoryCount(payment.paymentId()));
        assertEquals(0, fixtures.processedCount(eventId));
    }

    private UUID newEventId() {
        var eventId = UUID.randomUUID();
        eventIds.add(eventId);
        return eventId;
    }

    private static RefundRequestedPayload payload(CreatedPayment payment, UnaryOperator<RefundRequestedPayload> change) {
        return change.apply(new RefundRequestedPayload(payment.reservationId(), payment.paymentId(), RefundFixtures.USER_ID,
                BigDecimal.valueOf(RefundFixtures.AMOUNT), "KRW", "CANCELED"));
    }
}
