package com.iseonjwa.mockpg.fault;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.iseonjwa.mockpg.fault.FaultTarget.APPROVE;
import static com.iseonjwa.mockpg.fault.FaultTarget.CANCEL;
import static com.iseonjwa.mockpg.fault.FaultTarget.PAYMENT_LOOKUP;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FaultInjectorTest {
    private final FaultInjector faults = new FaultInjector();

    @Test
    void 설정이_없으면_장애를_주입하지_않는다() {
        assertEquals(Fault.NONE, faults.claim(APPROVE, "o-1"));
    }

    @Test
    void 횟수를_정하면_그만큼만_적용하고_NONE으로_돌아간다() {
        faults.apply(FaultRules.of(APPROVE, FaultMode.DROP_RESPONSE, 2));

        assertEquals(FaultMode.DROP_RESPONSE, faults.claim(APPROVE, "o-1").mode());
        assertEquals(FaultMode.DROP_RESPONSE, faults.claim(APPROVE, "o-1").mode());
        assertEquals(Fault.NONE, faults.claim(APPROVE, "o-1"));
    }

    @Test
    void 횟수를_정하지_않으면_해제할_때까지_계속_적용한다() {
        faults.apply(FaultRules.of(APPROVE, FaultMode.DROP_RESPONSE, null));

        for (int i = 0; i < 5; i++) {
            assertEquals(FaultMode.DROP_RESPONSE, faults.claim(APPROVE, "o-1").mode());
        }
        faults.apply(FaultRules.off(APPROVE));
        assertEquals(Fault.NONE, faults.claim(APPROVE, "o-1"));
    }

    @Test
    void 지연_설정은_지연_시간을_함께_돌려준다() {
        faults.apply(FaultRules.delay(PAYMENT_LOOKUP, 300, 1));

        assertEquals(new Fault(FaultMode.DELAY, 300), faults.claim(PAYMENT_LOOKUP, "o-1"));
    }

    @Test
    void 주문이_다른_요청은_적용받지_않고_횟수도_쓰지_않는다() {
        faults.apply(FaultRules.forOrder(APPROVE, FaultMode.PROCESSING, 1, "o-1"));

        assertEquals(Fault.NONE, faults.claim(APPROVE, "o-2"));
        assertEquals(FaultMode.PROCESSING, faults.claim(APPROVE, "o-1").mode());
        assertEquals(Fault.NONE, faults.claim(APPROVE, "o-1"));
    }

    @Test
    void 확률이_0이면_적용하지_않고_횟수도_쓰지_않는다() {
        faults.apply(new FaultRule(APPROVE, FaultMode.PROCESSING, null, 0.0, 1, null));

        for (int i = 0; i < 100; i++) {
            assertEquals(Fault.NONE, faults.claim(APPROVE, "o-1"));
        }
        assertEquals(1, faults.currentRules().stream().filter(r -> r.target() == APPROVE).findFirst().orElseThrow().count());
    }

    @Test
    void 확률이_1이면_항상_적용한다() {
        faults.apply(new FaultRule(APPROVE, FaultMode.PROCESSING, null, 1.0, null, null));

        for (int i = 0; i < 100; i++) {
            assertEquals(FaultMode.PROCESSING, faults.claim(APPROVE, "o-1").mode());
        }
    }

    @Test
    void 호출_종류가_다른_설정은_서로_영향을_주지_않는다() {
        faults.apply(FaultRules.of(APPROVE, FaultMode.PROCESSING, 1));
        faults.apply(FaultRules.of(CANCEL, FaultMode.SERVER_ERROR, 1));

        assertEquals(Fault.NONE, faults.claim(PAYMENT_LOOKUP, "o-1"));
        assertEquals(FaultMode.SERVER_ERROR, faults.claim(CANCEL, "o-1").mode());
        assertEquals(FaultMode.PROCESSING, faults.claim(APPROVE, "o-1").mode());
    }

    @Test
    void 한_번짜리_설정을_동시에_소비해도_정확히_한_요청에만_적용된다() throws Exception {
        faults.apply(FaultRules.of(APPROVE, FaultMode.DROP_RESPONSE, 1));
        var threads = 32;
        var start = new CountDownLatch(1);
        var results = new ArrayList<Future<Fault>>();

        try (var executor = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                Callable<Fault> claim = () -> {
                    start.await();
                    return faults.claim(APPROVE, "o-1");
                };
                results.add(executor.submit(claim));
            }
            start.countDown();
            var applied = 0;
            for (var result : results) {
                if (result.get().is(FaultMode.DROP_RESPONSE)) applied++;
            }

            assertEquals(1, applied);
        }
    }
}
