package com.iseonjwa.mockpg.ledger;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerHolderTest {
    private final LedgerHolder holder = new LedgerHolder();

    @Test
    void 처리_중에_초기화되면_옛_요청은_옛_원장에만_쓰고_새_원장은_비어_있다() throws Exception {
        var deciding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var oldLedger = holder.current();

        try (var executor = Executors.newSingleThreadExecutor()) {
            var approval = executor.submit(() -> oldLedger.approve("order-1", () -> {
                deciding.countDown();
                await(release);
                return LedgerTest.approvedOf("order-1");
            }));
            assertTrue(deciding.await(5, TimeUnit.SECONDS));

            holder.reset();
            release.countDown();
            approval.get(5, TimeUnit.SECONDS);
        }

        assertInstanceOf(OrderState.Approved.class, oldLedger.find("order-1").orElseThrow());
        assertEquals(Optional.empty(), holder.current().find("order-1"));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
