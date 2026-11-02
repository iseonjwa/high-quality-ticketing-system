package com.iseonjwa.payment.refund.application;

import com.iseonjwa.common.messaging.retention.RetentionCleanup;
import com.iseonjwa.payment.support.RefundFixtures;
import com.iseonjwa.payment.support.RefundFixtures.CreatedPayment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * N11: {@code processed_events}가 정리로 지워진 뒤 같은 이벤트가 다시 전달돼도 환불이 둘이 되지 않는다
 * (events-and-reliability.md 9절). 정리는 실제 정리 쿼리를 부르고, 기간 경과를 만들려고 기준 시각만 미래로 준다.
 */
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class RedeliveryAfterRetentionCleanupTest {
    @Autowired
    RefundRequestService refundRequests;
    @Autowired
    DeadLetterService deadLetters;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager transactionManager;

    private RefundFixtures fixtures;
    private RetentionCleanup cleanup;
    private final List<UUID> eventIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fixtures = new RefundFixtures(jdbc);
        cleanup = new RetentionCleanup(jdbc, transactionManager);
        fixtures.cleanUp(List.of());
    }

    @AfterEach
    void tearDown() {
        for (var eventId : eventIds) jdbc.update("DELETE FROM dead_letters WHERE event_key = ?", eventId.toString());
        fixtures.cleanUp(eventIds);
    }

    @Test
    void 처리_이력이_정리된_뒤_같은_RefundRequested가_다시_와도_환불은_한_건이다() {
        var payment = fixtures.payment("APPROVED");
        var eventId = newEventId();
        refundRequests.handle(eventId, payload(payment));
        assertEquals(1, fixtures.processedCount(eventId));

        purgeProcessedEvents();
        assertEquals(0, fixtures.processedCount(eventId));

        refundRequests.handle(eventId, payload(payment));

        assertEquals(1, fixtures.refundCount(payment.paymentId()));
        assertEquals(1, fixtures.refundHistoryCount(payment.paymentId()));
        // 지워진 이력은 재전달을 다시 처리하며 한 건으로 되살아난다. 환불을 만들지 못하게 막는 쪽은 refunds의 UNIQUE와 조건부 INSERT다
        assertEquals(1, fixtures.processedCount(eventId));
    }

    @Test
    void 처리_이력이_정리된_뒤_새_eventId로_같은_환불이_와도_환불은_한_건이다() {
        var payment = fixtures.payment("APPROVED");
        refundRequests.handle(newEventId(), payload(payment));

        purgeProcessedEvents();
        refundRequests.handle(newEventId(), payload(payment));

        assertEquals(1, fixtures.refundCount(payment.paymentId()));
        assertEquals(1, fixtures.refundHistoryCount(payment.paymentId()));
    }

    @Test
    void DLQ_처리기_경로도_처리_이력이_정리된_뒤_재전달에_환불은_한_건이다() {
        var payment = fixtures.payment("APPROVED");
        var eventId = newEventId();
        var input = dlqInput(eventId);
        deadLetters.handleParsed(input, eventId, payload(payment));
        assertEquals(1, fixtures.refundCount(payment.paymentId()));
        assertEquals(1, fixtures.processedCount(eventId));

        purgeProcessedEvents();
        deadLetters.handleParsed(input, eventId, payload(payment));

        assertEquals(1, fixtures.refundCount(payment.paymentId()));
        assertEquals(1, fixtures.refundHistoryCount(payment.paymentId()));
        // 이 경로의 중복 방어는 dead_letters.event_key UNIQUE라 처리 이력은 다시 쓰이지 않고, DLQ 기록도 한 건 그대로다
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM dead_letters WHERE event_key = ?", Integer.class,
                eventId.toString()));
    }

    @Test
    void 일반_경로로_환불된_이벤트가_처리_이력_정리_뒤_DLQ로_와도_환불은_한_건이다() {
        var payment = fixtures.payment("APPROVED");
        var eventId = newEventId();
        refundRequests.handle(eventId, payload(payment));

        purgeProcessedEvents();
        deadLetters.handleParsed(dlqInput(eventId), eventId, payload(payment));

        assertEquals(1, fixtures.refundCount(payment.paymentId()));
        assertEquals(1, fixtures.refundHistoryCount(payment.paymentId()));
    }

    // 기준 시각을 미래로 줘서 방금 쓴 이력도 기간이 지난 것으로 만든다
    private void purgeProcessedEvents() {
        assertEquals(true, cleanup.deleteProcessedEvents(Instant.now().plus(1, ChronoUnit.DAYS), 1_000) >= 1);
    }

    private DeadLetterInput dlqInput(UUID eventId) {
        return new DeadLetterInput("payment.command", 0, Math.abs(eventId.getLeastSignificantBits() % 1_000_000_000L),
                "key-" + eventId, "{}");
    }

    private UUID newEventId() {
        var eventId = UUID.randomUUID();
        eventIds.add(eventId);
        return eventId;
    }

    private static RefundRequestedPayload payload(CreatedPayment payment) {
        return new RefundRequestedPayload(payment.reservationId(), payment.paymentId(), RefundFixtures.USER_ID,
                BigDecimal.valueOf(RefundFixtures.AMOUNT), "KRW", "CANCELED");
    }
}
