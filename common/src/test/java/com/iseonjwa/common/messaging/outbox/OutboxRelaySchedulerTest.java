package com.iseonjwa.common.messaging.outbox;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxRelaySchedulerTest {
    private static final int BATCH = 20;

    private OutboxRelayScheduler scheduler;

    @AfterEach
    void stop() {
        if (scheduler != null) scheduler.stop();
    }

    @Test
    void 가득_집어_모두_보냈으면_상한까지_이어서_비운다() {
        var relay = new StubRelay(call -> new OutboxRelay.Result(BATCH, BATCH));
        scheduler = scheduler(relay);

        scheduler.relayRounds();

        assertEquals(OutboxRelayScheduler.MAX_ROUNDS, relay.calls.get());
    }

    @Test
    void 배치를_채우지_못했으면_한_번으로_멈춘다() {
        var relay = new StubRelay(call -> new OutboxRelay.Result(BATCH - 1, BATCH - 1));
        scheduler = scheduler(relay);

        scheduler.relayRounds();

        assertEquals(1, relay.calls.get());
    }

    @Test
    void 집은_행을_다_보내지_못했으면_이어_가지_않는다() {
        var relay = new StubRelay(call -> new OutboxRelay.Result(BATCH, BATCH - 1));
        scheduler = scheduler(relay);

        scheduler.relayRounds();

        assertEquals(1, relay.calls.get());
    }

    @Test
    void 전용_스레드_outbox_relay에서_주기적으로_돈다() throws Exception {
        var enough = new CountDownLatch(3);
        var threadName = new AtomicReference<String>();
        var relay = new StubRelay(call -> {
            threadName.set(Thread.currentThread().getName());
            enough.countDown();
            return new OutboxRelay.Result(0, 0);
        });
        scheduler = scheduler(relay);

        scheduler.start();

        assertTrue(enough.await(5, TimeUnit.SECONDS), "주기적으로 여러 번 돌아야 한다");
        assertEquals("outbox-relay", threadName.get());
        assertTrue(scheduler.isRunning());
    }

    @Test
    void 한_주기에서_예외나_Error가_나도_다음_주기를_계속한다() throws Exception {
        var enough = new CountDownLatch(4);
        var relay = new StubRelay(call -> {
            enough.countDown();
            if (call == 1) throw new IllegalStateException("DB 장애");
            if (call == 2) throw new AssertionError("Error도 주기를 멈추지 못한다");
            return new OutboxRelay.Result(0, 0);
        });
        scheduler = scheduler(relay);

        scheduler.start();

        assertTrue(enough.await(5, TimeUnit.SECONDS), "예외 뒤에도 주기가 이어져야 한다");
    }

    @Test
    void 종료하면_진행_중인_실행은_끝까지_기다리고_새_라운드는_시작하지_않는다() throws Exception {
        var inRelay = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var relay = new StubRelay(call -> {
            inRelay.countDown();
            await(release);
            // 가득 집어 모두 보냈으니 종료 중이 아니면 다음 라운드로 이어진다
            return new OutboxRelay.Result(BATCH, BATCH);
        });
        scheduler = new OutboxRelayScheduler(relay, Duration.ofMillis(20), Duration.ofSeconds(5));
        scheduler.start();
        assertTrue(inRelay.await(5, TimeUnit.SECONDS));

        var stopper = new Thread(scheduler::stop);
        stopper.start();
        Thread.sleep(100);
        assertTrue(stopper.isAlive(), "진행 중인 실행이 끝날 때까지 종료가 기다린다");
        release.countDown();
        stopper.join(5000);

        assertFalse(stopper.isAlive());
        assertEquals(1, relay.calls.get(), "종료 중에는 다음 라운드도 다음 주기도 시작하지 않는다");
        assertFalse(scheduler.isRunning());
    }

    @Test
    void 종료_대기를_넘기면_인터럽트하고_끝낸다() throws Exception {
        var interrupted = new CountDownLatch(1);
        var relay = new StubRelay(call -> {
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return new OutboxRelay.Result(0, 0);
        });
        scheduler = new OutboxRelayScheduler(relay, Duration.ofMillis(20), Duration.ofMillis(100));
        scheduler.start();
        var deadline = System.nanoTime() + 5_000_000_000L;
        while (relay.calls.get() == 0 && System.nanoTime() < deadline) Thread.onSpinWait();

        scheduler.stop();

        assertFalse(scheduler.isRunning());
        assertTrue(interrupted.await(5, TimeUnit.SECONDS), "대기를 넘기면 실행 중인 스레드를 인터럽트한다");
    }

    @Test
    void 두_번_시작해도_실행기는_하나다() throws Exception {
        var relay = new StubRelay(call -> new OutboxRelay.Result(0, 0));
        scheduler = scheduler(relay);

        scheduler.start();
        scheduler.start();
        scheduler.stop();
        scheduler.stop();

        assertFalse(scheduler.isRunning());
    }

    @Test
    void 라운드가_예외_없이_끝나면_마지막_성공_시각을_갱신한다_전송이_실패해도_마찬가지다() {
        var meters = new SimpleMeterRegistry();
        var clock = new FixedTime(Instant.parse("2026-11-03T00:00:00Z"));
        // 집은 행을 하나도 못 보냈다(브로커 장애). 라운드는 정상으로 끝난다
        var relay = new StubRelay(call -> new OutboxRelay.Result(BATCH, 0));
        scheduler = new OutboxRelayScheduler(relay, Duration.ofMillis(20), Duration.ofSeconds(5),
                new OutboxMetrics(meters, clock), clock);
        var started = clock.instant().getEpochSecond();
        assertEquals(started, meters.get("outbox.relay.last.success.timestamp").gauge().value(), "기동 시각으로 시작한다");
        clock.now = clock.now.plusSeconds(60);

        scheduler.runTick();

        assertEquals(started + 60, meters.get("outbox.relay.last.success.timestamp").gauge().value());
        assertEquals(0, meters.get("outbox.relay.failures").counter().count());
    }

    @Test
    void 라운드가_예외나_Error로_끝나면_실패_수만_올린다() {
        var meters = new SimpleMeterRegistry();
        var clock = new FixedTime(Instant.parse("2026-11-03T00:00:00Z"));
        var relay = new StubRelay(call -> {
            if (call == 1) throw new IllegalStateException("DB 장애");
            throw new AssertionError("Error도 센다");
        });
        scheduler = new OutboxRelayScheduler(relay, Duration.ofMillis(20), Duration.ofSeconds(5),
                new OutboxMetrics(meters, clock), clock);
        var started = clock.instant().getEpochSecond();
        clock.now = clock.now.plusSeconds(60);

        scheduler.runTick();
        scheduler.runTick();

        assertEquals(2, meters.get("outbox.relay.failures").counter().count());
        assertEquals(started, meters.get("outbox.relay.last.success.timestamp").gauge().value());
    }

    @Test
    void 지표를_끄면_예외가_나도_기록_없이_다음_주기를_계속한다() {
        var relay = new StubRelay(call -> {
            throw new IllegalStateException("DB 장애");
        });
        scheduler = scheduler(relay);

        scheduler.runTick();
        scheduler.runTick();

        assertEquals(2, relay.calls.get());
    }

    private static final class FixedTime extends Clock {
        Instant now;

        FixedTime(Instant now) {
            this.now = now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static OutboxRelayScheduler scheduler(OutboxRelay relay) {
        return new OutboxRelayScheduler(relay, Duration.ofMillis(20), Duration.ofSeconds(5));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    // 저장소와 sender 없이 relayOnce의 결과만 정해 주는 릴레이
    private static final class StubRelay extends OutboxRelay {
        final AtomicInteger calls = new AtomicInteger();
        private final IntFunction<Result> behavior;

        StubRelay(IntFunction<Result> behavior) {
            super(null, null, Clock.systemUTC(), new OutboxProperties(BATCH, Duration.ofSeconds(30), Duration.ofSeconds(12)));
            this.behavior = behavior;
        }

        @Override
        public Result relayOnce() {
            return behavior.apply(calls.incrementAndGet());
        }
    }
}
