package com.iseonjwa.payment.refund.infrastructure.persistence;

import com.iseonjwa.payment.refund.domain.RefundRepository;
import com.iseonjwa.payment.refund.domain.RefundStatus;
import com.iseonjwa.payment.support.RefundWorkerFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 환불 워커의 후보 선택과 임대·전이 쿼리(events-and-reliability.md 6절). 행 잠금이 트랜잭션 단위라 커밋되는 행으로 확인한다.
 */
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class RefundWorkerQueryTest {
    @Autowired
    RefundRepository refunds;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate tx;

    private RefundWorkerFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = RefundWorkerFixtures.forService(jdbc);
        fixtures.cleanUp();
        fixtures.parkAllRefunds();
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanUp();
    }

    @Test
    void 차례가_지난_REQUESTED와_UNKNOWN을_next_check_at_순으로_고른다() {
        fixtures.create(RefundWorkerFixtures.spec().refundStatus("UNKNOWN").nextCheckAt(Instant.now().minusSeconds(5)));
        var earlier = fixtures.create(RefundWorkerFixtures.spec().nextCheckAt(Instant.now().minusSeconds(60)));

        var first = tx.execute(status -> refunds.findDueForUpdate(Instant.now()).orElseThrow().getId());

        assertEquals(earlier.refundId(), first);
    }

    @Test
    void 차례가_오지_않았거나_종결된_환불은_고르지_않는다() {
        fixtures.create(RefundWorkerFixtures.spec().nextCheckAt(Instant.now().plusSeconds(60)));
        fixtures.create(RefundWorkerFixtures.spec().refundStatus("NEEDS_MANUAL_CHECK"));
        fixtures.create(RefundWorkerFixtures.spec().refundStatus("REFUNDED"));

        var found = tx.execute(status -> refunds.findDueForUpdate(Instant.now()));

        assertFalse(found.isPresent());
    }

    @Test
    void 다른_트랜잭션이_잠근_환불은_기다리지_않고_건너뛴다() throws Exception {
        var only = fixtures.due();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var holder = executor.submit(() -> tx.executeWithoutResult(status -> {
                refunds.findDueForUpdate(Instant.now()).orElseThrow();
                locked.countDown();
                await(release);
            }));
            assertTrue(locked.await(5, TimeUnit.SECONDS));

            var skipped = tx.execute(status -> refunds.findDueForUpdate(Instant.now()));

            assertFalse(skipped.isPresent(), "잠긴 환불 " + only.refundId() + "를 건너뛰어야 한다");
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void 임대하면_다음_확인_시각이_밀리고_시도_횟수가_늘고_pg_requested_at이_채워진다() {
        var refund = fixtures.create(RefundWorkerFixtures.spec().refundStatus("UNKNOWN").attempt(2)
                .pgRequestedAt(Instant.now().minusSeconds(120)));
        var leaseUntil = Instant.now().plusSeconds(30);

        Boolean claimed = tx.execute(status -> refunds.claim(refund.refundId(), RefundStatus.UNKNOWN, leaseUntil, Instant.now()));
        assertTrue(claimed);

        assertEquals(3, fixtures.refundAttempt(refund));
        assertEquals(leaseUntil.toEpochMilli(), fixtures.refundNextCheckAt(refund).toEpochMilli());
        assertEquals("UNKNOWN", fixtures.refundStatus(refund));
    }

    @Test
    void 임대는_pg_requested_at을_처음만_채우고_이후에는_유지한다() {
        var refund = fixtures.due();
        assertNull(fixtures.refundPgRequestedAt(refund));
        var firstClaim = Instant.now().minusSeconds(1);

        assertTrue(refunds.claim(refund.refundId(), RefundStatus.REQUESTED, Instant.now().minusSeconds(1), firstClaim));
        var filled = fixtures.refundPgRequestedAt(refund);
        assertNotNull(filled);
        assertEquals(firstClaim.toEpochMilli(), filled.toEpochMilli());

        // 임대가 끝나 다시 차례가 된 두 번째 임대는 처음 시각을 덮어쓰지 않는다
        assertTrue(refunds.claim(refund.refundId(), RefundStatus.REQUESTED, Instant.now().plusSeconds(30), Instant.now()));
        assertEquals(filled.toEpochMilli(), fixtures.refundPgRequestedAt(refund).toEpochMilli());
        assertEquals(2, fixtures.refundAttempt(refund));
    }

    @Test
    void 상태가_바뀌었거나_차례가_아닌_환불은_임대하지_않는다() {
        var changed = fixtures.create(RefundWorkerFixtures.spec().refundStatus("UNKNOWN").attempt(1));
        var notYet = fixtures.create(RefundWorkerFixtures.spec().nextCheckAt(Instant.now().plusSeconds(60)));

        assertFalse(refunds.claim(changed.refundId(), RefundStatus.REQUESTED, Instant.now().plusSeconds(30), Instant.now()));
        assertFalse(refunds.claim(notYet.refundId(), RefundStatus.REQUESTED, Instant.now().plusSeconds(30), Instant.now()));

        assertEquals(1, fixtures.refundAttempt(changed));
        assertEquals(0, fixtures.refundAttempt(notYet));
        assertNull(fixtures.refundPgRequestedAt(notYet), "임대하지 못했으면 pg_requested_at도 채우지 않는다");
    }

    @Test
    void REFUNDED_전이는_현재_상태가_맞을_때만_1행이고_확정_값을_남긴다() {
        var refund = fixtures.create(RefundWorkerFixtures.spec().refundStatus("UNKNOWN").attempt(1));
        var refundedAt = Instant.parse("2026-11-02T11:00:01.100Z");

        assertFalse(refunds.markRefunded(refund.refundId(), RefundStatus.REQUESTED, "ref-1", refundedAt, Instant.now()));
        assertEquals("UNKNOWN", fixtures.refundStatus(refund));
        assertTrue(refunds.markRefunded(refund.refundId(), RefundStatus.UNKNOWN, "ref-1", refundedAt, Instant.now()));

        var row = fixtures.refundRow(refund);
        assertEquals("REFUNDED", row.get("status"));
        assertEquals("ref-1", row.get("pg_refund_ref"));
        assertNull(row.get("next_check_at"), "확정된 환불은 워커 대상이 아니다");
        assertEquals(refundedAt, ((java.sql.Timestamp) row.get("refunded_at")).toInstant());
        assertFalse(refunds.markRefunded(refund.refundId(), RefundStatus.UNKNOWN, "ref-2", refundedAt, Instant.now()),
                "이미 확정된 환불은 다시 바뀌지 않는다");
        assertEquals("ref-1", fixtures.refundRow(refund).get("pg_refund_ref"));
    }

    @Test
    void UNKNOWN_전이는_REQUESTED_환불만_1행이고_다음_확인_시각을_정한다() {
        var requested = fixtures.due();
        var unknown = fixtures.create(RefundWorkerFixtures.spec().refundStatus("UNKNOWN"));
        var next = Instant.now().plusSeconds(10);

        assertTrue(refunds.markUnknown(requested.refundId(), next, Instant.now()));
        assertFalse(refunds.markUnknown(unknown.refundId(), next, Instant.now()));

        assertEquals("UNKNOWN", fixtures.refundStatus(requested));
        assertEquals(next.toEpochMilli(), fixtures.refundNextCheckAt(requested).toEpochMilli());
    }

    @Test
    void 재예약은_UNKNOWN_환불의_다음_확인_시각만_바꾼다() {
        var unknown = fixtures.create(RefundWorkerFixtures.spec().refundStatus("UNKNOWN").attempt(2));
        var requested = fixtures.due();
        var next = Instant.now().plusSeconds(600);

        assertTrue(refunds.reschedule(unknown.refundId(), next, Instant.now()));
        assertFalse(refunds.reschedule(requested.refundId(), next, Instant.now()));

        assertEquals(next.toEpochMilli(), fixtures.refundNextCheckAt(unknown).toEpochMilli());
        assertEquals(2, fixtures.refundAttempt(unknown));
        assertEquals("REQUESTED", fixtures.refundStatus(requested));
    }

    @Test
    void NEEDS_MANUAL_CHECK_전이는_현재_상태가_맞을_때만_1행이고_대상에서_뺀다() {
        var refund = fixtures.due();

        assertFalse(refunds.markNeedsManualCheck(refund.refundId(), RefundStatus.UNKNOWN, Instant.now()));
        assertTrue(refunds.markNeedsManualCheck(refund.refundId(), RefundStatus.REQUESTED, Instant.now()));

        assertEquals("NEEDS_MANUAL_CHECK", fixtures.refundStatus(refund));
        assertNull(fixtures.refundRow(refund).get("next_check_at"));
        assertFalse(refunds.markNeedsManualCheck(refund.refundId(), RefundStatus.REQUESTED, Instant.now()));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
