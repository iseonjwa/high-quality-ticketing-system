package com.iseonjwa.mockpg.ledger;

import com.iseonjwa.mockpg.ledger.OrderState.Approved;
import com.iseonjwa.mockpg.ledger.OrderState.Canceled;
import com.iseonjwa.mockpg.ledger.OrderState.Declined;
import com.iseonjwa.mockpg.ledger.OrderState.InFlight;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * mock-pg.md 4절 상태 표(현재 상태 × 승인·망취소·조회)를 칸마다 확인한다.
 */
class LedgerTest {
    private final Ledger ledger = new Ledger();
    private final String orderId = UUID.randomUUID().toString();

    @Nested
    class 승인 {
        @Test
        void 처음_보는_주문은_결정한_결과로_확정하고_승인_색인에_넣는다() {
            var approved = approvedOf(orderId);

            assertEquals(approved, ledger.approve(orderId, () -> approved));
            assertEquals(Optional.of(approved), ledger.find(orderId));
            assertEquals(Optional.of(approved), ledger.findApproved(approved.pgTxId()));
        }

        @Test
        void 처리_중인_주문은_결정하지_않고_처리_중을_돌려준다() {
            var seen = new OrderState[1];

            ledger.approve(orderId, () -> {
                seen[0] = ledger.approve(orderId, LedgerTest::notCalled);
                return approvedOf(orderId);
            });

            assertInstanceOf(InFlight.class, seen[0]);
        }

        @Test
        void 승인된_주문은_저장한_승인을_그대로_돌려준다() {
            var approved = approvedOf(orderId);
            ledger.approve(orderId, () -> approved);

            assertEquals(approved, ledger.approve(orderId, LedgerTest::notCalled));
        }

        @Test
        void 거절된_주문은_저장한_거절을_그대로_돌려준다() {
            var declined = new Declined("CARD_DECLINED");
            ledger.approve(orderId, () -> declined);

            assertEquals(declined, ledger.approve(orderId, LedgerTest::notCalled));
        }

        @Test
        void 망취소된_주문은_승인하지_않고_망취소를_돌려준다() {
            ledger.cancel(orderId);

            assertEquals(Canceled.INSTANCE, ledger.approve(orderId, LedgerTest::notCalled));
        }

        @Test
        void 결정_중_예외가_나면_처리_중_표시를_지워_주문이_없던_상태로_돌아간다() {
            var failure = new IllegalStateException("승인 스레드 실패");

            var thrown = assertThrows(IllegalStateException.class, () -> ledger.approve(orderId, () -> {
                throw failure;
            }));

            assertEquals(failure, thrown);
            assertEquals(Optional.empty(), ledger.find(orderId));
            var retried = approvedOf(orderId);
            assertEquals(retried, ledger.approve(orderId, () -> retried));
        }

        @Test
        void 결과를_정하지_못하면_처리_중_표시를_지운다() {
            assertThrows(NullPointerException.class, () -> ledger.approve(orderId, () -> null));

            assertEquals(Optional.empty(), ledger.find(orderId));
        }
    }

    @Nested
    class 망취소 {
        @Test
        void 처음_보는_주문은_망취소로_바꾼다() {
            assertEquals(Canceled.INSTANCE, ledger.cancel(orderId));
            assertEquals(Optional.of(Canceled.INSTANCE), ledger.find(orderId));
        }

        @Test
        void 처리_중인_주문은_바꾸지_않아_승인이_그대로_확정된다() {
            var seen = new OrderState[1];
            var approved = approvedOf(orderId);

            ledger.approve(orderId, () -> {
                seen[0] = ledger.cancel(orderId);
                return approved;
            });

            assertInstanceOf(InFlight.class, seen[0]);
            assertEquals(Optional.of(approved), ledger.find(orderId));
        }

        @Test
        void 승인된_주문은_승인을_돌려준다() {
            var approved = approvedOf(orderId);
            ledger.approve(orderId, () -> approved);

            assertEquals(approved, ledger.cancel(orderId));
        }

        @Test
        void 거절된_주문은_거절_상태를_유지한다() {
            var declined = new Declined("CARD_DECLINED");
            ledger.approve(orderId, () -> declined);

            assertEquals(declined, ledger.cancel(orderId));
            assertEquals(Optional.of(declined), ledger.find(orderId));
        }

        @Test
        void 망취소된_주문은_망취소를_돌려준다() {
            ledger.cancel(orderId);

            assertEquals(Canceled.INSTANCE, ledger.cancel(orderId));
        }
    }

    @Test
    void 처음_보는_주문은_조회해도_없다() {
        assertEquals(Optional.empty(), ledger.find(orderId));
    }

    /**
     * INV-8: 망취소가 CANCELED를 응답했다면 그 주문은 이후 APPROVED가 될 수 없다. 승인과 망취소를 실제 스레드로 경합시킨다.
     */
    @Test
    void 승인과_망취소가_동시에_와도_망취소를_받은_주문은_승인되지_않는다() throws Exception {
        var races = new ArrayList<Race>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 1_000; i++) {
                var id = "race-" + i;
                var start = new CountDownLatch(1);
                var approval = executor.submit(() -> {
                    start.await();
                    return ledger.approve(id, () -> {
                        // 처리 중 구간을 조금 넓혀 망취소가 끼어들 기회를 만든다
                        LockSupport.parkNanos(10_000);
                        return approvedOf(id);
                    });
                });
                var cancel = executor.submit(() -> {
                    start.await();
                    return ledger.cancel(id);
                });
                start.countDown();
                races.add(new Race(id, approval, cancel));
            }
        }

        var canceledFirst = new AtomicInteger();
        for (var race : races) {
            var canceled = race.cancel().get();
            var approved = race.approval().get();
            var finalState = ledger.find(race.orderId()).orElseThrow();
            if (canceled instanceof Canceled) {
                canceledFirst.incrementAndGet();
                assertEquals(Canceled.INSTANCE, finalState, race.orderId());
                assertEquals(Canceled.INSTANCE, approved, race.orderId());
            } else {
                // 망취소가 처리 중이나 승인을 봤다면 승인이 확정된다
                assertTrue(canceled instanceof InFlight || canceled instanceof Approved, race.orderId());
                assertInstanceOf(Approved.class, finalState, race.orderId());
            }
        }
        System.out.printf("경합 1,000회 중 망취소가 먼저 이긴 주문 %d건%n", canceledFirst.get());
    }

    private record Race(String orderId, Future<OrderState> approval, Future<OrderState> cancel) {
    }

    static Approved approvedOf(String orderId) {
        return new Approved(orderId, "pg-" + UUID.randomUUID(), BigDecimal.valueOf(50000), "KRW", Instant.now());
    }

    private static OrderState.Decided notCalled() {
        return fail("이미 상태가 있는 주문의 결과를 다시 정했다");
    }
}
