package com.iseonjwa.payment.payment.infrastructure.persistence;

import com.iseonjwa.payment.payment.domain.PaymentRepository;
import com.iseonjwa.payment.payment.domain.PaymentStatus;
import com.iseonjwa.payment.support.RecheckFixtures;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 재확인 후보 선택과 임대·재예약 쿼리(events-and-reliability.md 6절). 행 잠금이 트랜잭션 단위라 커밋되는 행으로 확인한다.
 */
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class PaymentRecheckQueryTest {
    @Autowired
    PaymentRepository payments;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate tx;

    private RecheckFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new RecheckFixtures(payments, jdbc);
        fixtures.parkOtherDuePayments();
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanUp();
    }

    @Test
    void 차례가_지난_REQUESTED와_UNKNOWN을_next_check_at_순으로_고른다() {
        fixtures.create(PaymentStatus.UNKNOWN, 2, Instant.now().minusSeconds(5));
        var earlier = fixtures.create(PaymentStatus.REQUESTED, 0, Instant.now().minusSeconds(60));

        var first = tx.execute(status -> payments.findDueForRecheckForUpdate(Instant.now()).orElseThrow().getId());

        assertEquals(earlier.getId(), first);
    }

    @Test
    void 차례가_오지_않았거나_종결된_결제는_고르지_않는다() {
        fixtures.create(PaymentStatus.UNKNOWN, 1, Instant.now().plusSeconds(60));
        fixtures.create(PaymentStatus.NEEDS_MANUAL_CHECK, 9, Instant.now().minusSeconds(60));

        var found = tx.execute(status -> payments.findDueForRecheckForUpdate(Instant.now()));

        assertFalse(found.isPresent());
    }

    @Test
    void 다른_트랜잭션이_잠근_결제는_기다리지_않고_건너뛴다() throws Exception {
        var only = fixtures.due(PaymentStatus.REQUESTED, 0);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var holder = executor.submit(() -> tx.executeWithoutResult(status -> {
                payments.findDueForRecheckForUpdate(Instant.now()).orElseThrow();
                locked.countDown();
                await(release);
            }));
            assertTrue(locked.await(5, TimeUnit.SECONDS));

            var skipped = tx.execute(status -> payments.findDueForRecheckForUpdate(Instant.now()));

            assertFalse(skipped.isPresent(), "잠긴 결제 " + only.getId() + "를 건너뛰어야 한다");
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void 임대하면_다음_확인_시각이_밀리고_시도_횟수가_는다() {
        var payment = fixtures.due(PaymentStatus.UNKNOWN, 3);
        var leaseUntil = Instant.now().plusSeconds(30);

        assertTrue(payments.claimRecheck(payment.getId(), PaymentStatus.UNKNOWN, leaseUntil, Instant.now()));

        var claimed = fixtures.reload(payment);
        assertEquals(4, claimed.getUnknownRetryCount());
        assertEquals(leaseUntil.toEpochMilli(), claimed.getNextCheckAt().toEpochMilli());
        assertEquals(PaymentStatus.UNKNOWN, claimed.getStatus());
    }

    @Test
    void 상태가_바뀐_결제는_임대하지_않는다() {
        var payment = fixtures.due(PaymentStatus.UNKNOWN, 3);

        assertFalse(payments.claimRecheck(payment.getId(), PaymentStatus.REQUESTED, Instant.now().plusSeconds(30),
                Instant.now()));

        assertEquals(3, fixtures.reload(payment).getUnknownRetryCount());
    }

    @Test
    void 재예약은_UNKNOWN_결제의_다음_확인_시각만_바꾼다() {
        var unknown = fixtures.due(PaymentStatus.UNKNOWN, 2);
        var requested = fixtures.due(PaymentStatus.REQUESTED, 0);
        var next = Instant.now().plusSeconds(600);

        assertTrue(payments.rescheduleCheck(unknown.getId(), next, Instant.now()));
        assertFalse(payments.rescheduleCheck(requested.getId(), next, Instant.now()));

        assertEquals(next.toEpochMilli(), fixtures.reload(unknown).getNextCheckAt().toEpochMilli());
        assertEquals(2, fixtures.reload(unknown).getUnknownRetryCount());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
