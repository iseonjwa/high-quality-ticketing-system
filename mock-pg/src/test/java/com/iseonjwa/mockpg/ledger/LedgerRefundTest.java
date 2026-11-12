package com.iseonjwa.mockpg.ledger;

import com.iseonjwa.mockpg.ledger.OrderState.Approved;
import com.iseonjwa.mockpg.ledger.RefundOutcome.Refunded;
import com.iseonjwa.mockpg.ledger.RefundOutcome.RejectCode;
import com.iseonjwa.mockpg.ledger.RefundOutcome.Rejected;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * mock-pg.md 8절 환불 규칙을 원장 수준에서 확인한다. 승인 금액은 50,000원이다.
 */
class LedgerRefundTest {
    private static final int REPEATS = 200;
    private static final int THREADS = 32;

    private final Ledger ledger = new Ledger();

    @Test
    void 성공하면_저장되고_환불_조회로_보인다() {
        var approved = approve(ledger);
        var refunded = refund("r-1", approved, 20_000);

        assertEquals(refunded, ledger.refund(refunded));
        assertEquals(Optional.of(refunded), ledger.findRefund("r-1"));
    }

    @Test
    void 부분_환불_둘의_합이_승인_금액이면_둘_다_성공하고_1원_더는_거절한다() {
        var approved = approve(ledger);

        assertInstanceOf(Refunded.class, ledger.refund(refund("r-1", approved, 20_000)));
        assertInstanceOf(Refunded.class, ledger.refund(refund("r-2", approved, 30_000)));

        assertEquals(new Rejected(RejectCode.NOT_REFUNDABLE), ledger.refund(refund("r-3", approved, 1)));
    }

    @Test
    void 환불_합계가_승인_금액을_넘으면_거절하고_누계를_늘리지_않는다() {
        var approved = approve(ledger);

        assertEquals(new Rejected(RejectCode.NOT_REFUNDABLE), ledger.refund(refund("r-1", approved, 50_001)));
        assertInstanceOf(Refunded.class, ledger.refund(refund("r-2", approved, 50_000)));
    }

    @Test
    void 같은_환불_식별자와_같은_결제의_재요청은_저장된_결과를_돌려주고_누계는_한_번만_는다() {
        var approved = approve(ledger);
        var first = ledger.refund(refund("r-1", approved, 20_000));

        var again = ledger.refund(refund("r-1", approved, 20_000));

        assertEquals(first, again);
        assertInstanceOf(Refunded.class, ledger.refund(refund("r-2", approved, 30_000)));
        assertEquals(new Rejected(RejectCode.NOT_REFUNDABLE), ledger.refund(refund("r-3", approved, 1)));
    }

    @Test
    void 같은_환불_식별자를_다른_결제로_보내면_OTHER다_다른_결제가_승인돼_있을_때() {
        var first = approve(ledger);
        var other = approve(ledger);
        ledger.refund(refund("r-1", first, 10_000));

        assertEquals(new Rejected(RejectCode.OTHER), ledger.refund(refund("r-1", other, 10_000)));
        assertEquals(Optional.empty(), ledger.findRefund("r-1").filter(r -> r.pgTxId().equals(other.pgTxId())));
    }

    @Test
    void 같은_환불_식별자를_다른_결제로_보내면_OTHER다_그_결제가_승인되지_않았어도() {
        var first = approve(ledger);
        ledger.refund(refund("r-1", first, 10_000));

        var unapproved = LedgerTest.approvedOf("o-unapproved");

        assertEquals(new Rejected(RejectCode.OTHER), ledger.refund(refund("r-1", unapproved, 10_000)));
        assertEquals(Optional.empty(), ledger.findApproved(unapproved.pgTxId()));
    }

    @Test
    void 승인되지_않은_결제의_환불은_거절하고_원장에_항목을_만들지_않는다() {
        var unapproved = LedgerTest.approvedOf("o-unapproved");

        assertEquals(new Rejected(RejectCode.NOT_REFUNDABLE), ledger.refund(refund("r-1", unapproved, 10_000)));

        assertEquals(Optional.empty(), ledger.findApproved(unapproved.pgTxId()));
        assertEquals(Optional.empty(), ledger.findRefund("r-1"));
    }

    @Test
    void 통화가_승인과_다르면_거절한다() {
        var approved = approve(ledger);
        var usd = new Refunded("r-1", "rf-" + UUID.randomUUID(), approved.pgTxId(), BigDecimal.valueOf(10_000), "USD",
                Instant.now());

        assertEquals(new Rejected(RejectCode.NOT_REFUNDABLE), ledger.refund(usd));
    }

    @Test
    void 거절은_저장하지_않아_결제를_승인한_뒤_같은_환불_식별자로_다시_보내면_성공한다() {
        var approved = LedgerTest.approvedOf("o-late");
        assertEquals(new Rejected(RejectCode.NOT_REFUNDABLE), ledger.refund(refund("r-1", approved, 10_000)));
        assertEquals(Optional.empty(), ledger.findRefund("r-1"));

        ledger.approve("o-late", () -> approved);

        assertInstanceOf(Refunded.class, ledger.refund(refund("r-1", approved, 10_000)));
    }

    @Test
    void 같은_환불_식별자로_동시에_환불해도_모두_같은_결과이고_누계는_한_번만_는다() throws Exception {
        try (var executor = Executors.newFixedThreadPool(THREADS)) {
            for (int round = 0; round < REPEATS; round++) {
                var ledger = new Ledger();
                var approved = approve(ledger);
                var start = new CountDownLatch(1);
                var futures = new ArrayList<Future<RefundOutcome>>();
                for (int i = 0; i < THREADS; i++) {
                    // 후보마다 refundTxId가 달라도 저장되는 것은 하나여야 한다
                    var candidate = refund("r-same", approved, 50_000);
                    Callable<RefundOutcome> task = () -> {
                        start.await();
                        return ledger.refund(candidate);
                    };
                    futures.add(executor.submit(task));
                }
                start.countDown();

                var results = new HashSet<RefundOutcome>();
                for (var future : futures) results.add(future.get());

                assertEquals(1, results.size(), "라운드 " + round);
                assertInstanceOf(Refunded.class, results.iterator().next());
                assertEquals(new Rejected(RejectCode.NOT_REFUNDABLE), ledger.refund(refund("r-next", approved, 1)),
                        "누계가 승인 금액과 정확히 같아야 한다 라운드 " + round);
            }
        }
    }

    @Test
    void 같은_환불_식별자를_다른_결제로_동시에_보내면_한_결제만_성공하고_진_결제의_누계는_늘지_않는다() throws Exception {
        try (var executor = Executors.newFixedThreadPool(THREADS)) {
            for (int round = 0; round < REPEATS; round++) {
                var ledger = new Ledger();
                var first = approve(ledger);
                var second = approve(ledger);
                var start = new CountDownLatch(1);
                var futures = new ArrayList<Future<RefundOutcome>>();
                for (int i = 0; i < THREADS; i++) {
                    var target = i % 2 == 0 ? first : second;
                    var candidate = refund("r-shared", target, 50_000);
                    Callable<RefundOutcome> task = () -> {
                        start.await();
                        return ledger.refund(candidate);
                    };
                    futures.add(executor.submit(task));
                }
                start.countDown();

                var winners = new HashSet<String>();
                for (var future : futures) {
                    if (future.get() instanceof Refunded refunded) winners.add(refunded.pgTxId());
                    else assertEquals(new Rejected(RejectCode.OTHER), future.get(), "라운드 " + round);
                }

                assertEquals(1, winners.size(), "라운드 " + round);
                var loser = winners.contains(first.pgTxId()) ? second : first;
                assertInstanceOf(Refunded.class, ledger.refund(refund("r-loser", loser, 50_000)),
                        "진 결제의 누계가 늘지 않아야 한다 라운드 " + round);
            }
        }
    }

    private final TxIdSigner signer = new TxIdSigner("secret-a");

    /** 서명한 거래 ID로 승인한 결제를 둔 원장을 새 원장으로 바꿔(재시작 흉내) 같은 거래 ID의 환불을 판정한다. */
    private RefundOutcome refundAfterRestart(Ledger restarted, String pgTxId, String refundId, long amount, String currency) {
        var candidate = new Refunded(refundId, "rf-" + UUID.randomUUID(), pgTxId, BigDecimal.valueOf(amount), currency, Instant.now());
        return restarted.refund(candidate, signer.verify(pgTxId));
    }

    private String signedTxId(Ledger original, long amount) {
        var orderId = "o-" + UUID.randomUUID();
        var approved = new Approved(orderId, signer.issue(BigDecimal.valueOf(amount), "KRW"), BigDecimal.valueOf(amount), "KRW",
                Instant.now());
        original.approve(orderId, () -> approved);
        return approved.pgTxId();
    }

    @Test
    void 원장이_바뀐_뒤에도_서명한_거래_ID는_환불된다() {
        var pgTxId = signedTxId(ledger, 50_000);
        var restarted = new Ledger();

        assertInstanceOf(Refunded.class, refundAfterRestart(restarted, pgTxId, "r-1", 20_000, "KRW"));
        assertEquals(Optional.empty(), restarted.findApproved(pgTxId), "주문은 되살리지 않는다");
        assertEquals(new java.math.BigDecimal("20000"), restarted.refundsOf(pgTxId).refundedTotal());
    }

    @Test
    void 되살린_뒤_부분_환불의_합이_승인_금액을_넘으면_거절한다() {
        var pgTxId = signedTxId(ledger, 50_000);
        var restarted = new Ledger();

        assertInstanceOf(Refunded.class, refundAfterRestart(restarted, pgTxId, "r-1", 20_000, "KRW"));
        assertInstanceOf(Refunded.class, refundAfterRestart(restarted, pgTxId, "r-2", 30_000, "KRW"));
        assertEquals(new Rejected(RejectCode.NOT_REFUNDABLE), refundAfterRestart(restarted, pgTxId, "r-3", 1, "KRW"));
    }

    @Test
    void 되살린_결제도_통화가_다르면_거절하고_항목을_남기지_않는다() {
        var pgTxId = signedTxId(ledger, 50_000);
        var restarted = new Ledger();

        assertEquals(new Rejected(RejectCode.NOT_REFUNDABLE), refundAfterRestart(restarted, pgTxId, "r-1", 10_000, "USD"));

        assertEquals(java.math.BigDecimal.ZERO, restarted.refundsOf(pgTxId).refundedTotal());
        assertEquals(Optional.empty(), restarted.findRefund("r-1"));
    }

    @Test
    void 서명이_없는_옛_형식_거래_ID는_거절한다() {
        var legacy = "pg-" + UUID.randomUUID();

        assertEquals(new Rejected(RejectCode.NOT_REFUNDABLE), refundAfterRestart(new Ledger(), legacy, "r-1", 10_000, "KRW"));
    }

    private static Approved approve(Ledger ledger) {
        var orderId = "o-" + UUID.randomUUID();
        var approved = LedgerTest.approvedOf(orderId);
        ledger.approve(orderId, () -> approved);
        return approved;
    }

    private static Refunded refund(String refundId, Approved approved, long amount) {
        return new Refunded(refundId, "rf-" + UUID.randomUUID(), approved.pgTxId(), BigDecimal.valueOf(amount), "KRW",
                Instant.now());
    }
}
