package com.iseonjwa.mockpg.fault;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FaultInjectorTest {
    private final FaultInjector faults = new FaultInjector();

    @Test
    void 설정이_없으면_장애를_주입하지_않는다() {
        assertEquals(FaultMode.NONE, faults.claim(FaultTarget.APPROVE));
    }

    @Test
    void 횟수를_정하면_그만큼만_적용하고_NONE으로_돌아간다() {
        faults.apply(new FaultRule(FaultTarget.APPROVE, FaultMode.DROP_RESPONSE, 2));

        assertEquals(FaultMode.DROP_RESPONSE, faults.claim(FaultTarget.APPROVE));
        assertEquals(FaultMode.DROP_RESPONSE, faults.claim(FaultTarget.APPROVE));
        assertEquals(FaultMode.NONE, faults.claim(FaultTarget.APPROVE));
    }

    @Test
    void 횟수를_정하지_않으면_해제할_때까지_계속_적용한다() {
        faults.apply(new FaultRule(FaultTarget.APPROVE, FaultMode.DROP_RESPONSE, null));

        for (int i = 0; i < 5; i++) {
            assertEquals(FaultMode.DROP_RESPONSE, faults.claim(FaultTarget.APPROVE));
        }
        faults.apply(new FaultRule(FaultTarget.APPROVE, FaultMode.NONE, null));
        assertEquals(FaultMode.NONE, faults.claim(FaultTarget.APPROVE));
    }

    @Test
    void 한_번짜리_설정을_동시에_소비해도_정확히_한_요청에만_적용된다() throws Exception {
        faults.apply(new FaultRule(FaultTarget.APPROVE, FaultMode.DROP_RESPONSE, 1));
        var threads = 32;
        var start = new CountDownLatch(1);
        var results = new ArrayList<Future<FaultMode>>();

        try (var executor = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                Callable<FaultMode> claim = () -> {
                    start.await();
                    return faults.claim(FaultTarget.APPROVE);
                };
                results.add(executor.submit(claim));
            }
            start.countDown();
            var applied = 0;
            for (var result : results) {
                if (result.get() == FaultMode.DROP_RESPONSE) applied++;
            }

            assertEquals(1, applied);
        }
    }

    @Test
    void 횟수는_1_이상이어야_한다() {
        assertThrows(IllegalArgumentException.class,
                () -> new FaultRule(FaultTarget.APPROVE, FaultMode.DROP_RESPONSE, 0));
    }
}
