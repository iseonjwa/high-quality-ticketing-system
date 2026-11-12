package com.iseonjwa.reservation.seathold.concurrency;

import com.iseonjwa.reservation.seathold.application.HoldSeatsCommand;
import com.iseonjwa.reservation.seathold.application.SeatHoldExpiryService;
import com.iseonjwa.reservation.seathold.application.SyncHoldOutcome;
import com.iseonjwa.reservation.seathold.application.SyncSeatHoldService;
import com.iseonjwa.reservation.seathold.concurrency.ConcurrentRunner.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.iseonjwa.reservation.seathold.concurrency.ConcurrentRunner.OK;
import static com.iseonjwa.reservation.seathold.concurrency.ConcurrentRunner.countByResult;
import static com.iseonjwa.reservation.seathold.concurrency.ConcurrentRunner.report;
import static com.iseonjwa.reservation.seathold.concurrency.ConcurrentRunner.runTogether;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ISE-79 좌석 선점 동시성 시나리오 C1~C7(docs/design/reservation/concurrency-scenarios.md 4절).
 *
 * <p>단언은 고친 뒤의 목표 기준이다. 기준선(MVP 코드)에서 실패하는 단언과 표준 출력의 {@code [SEATHOLD-CONCURRENCY]} 줄이
 * 문제 재현 기록이 되고, 고친 뒤 통과하면 회귀 테스트가 된다. 경합 결과는 확률적이라 시나리오마다 여러 라운드를 돈다.
 *
 * <p>Note: 기본 빌드에서는 돌지 않는다. 스레드 100개와 라운드 반복으로 시간이 걸리고, 기준선에서는 실패가 정상이기 때문이다.
 * {@code SEATHOLD_CONCURRENCY=true}를 주고 이 클래스만 실행한다. 데드락 수는 DB 전체 누적값의 차이라 다른 테스트와 함께 돌리지 않는다.
 */
@Tag("concurrency")
@EnabledIfEnvironmentVariable(named = "SEATHOLD_CONCURRENCY", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class SeatHoldConcurrencyTest {
    private static final long BASE_USER = 790_000L;
    private static final int MAX_PER_USER = 4;

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SyncSeatHoldService seatHold;
    @Autowired
    SeatHoldExpiryService expiry;
    @Autowired
    PlatformTransactionManager transactionManager;

    private SeatHoldFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new SeatHoldFixture(jdbc);
    }

    @AfterEach
    void cleanUp() {
        fixture.cleanUp();
    }

    // C1(E1): 같은 좌석에 100명이 동시에 선택 완료하면 1명만 선점하고 99명은 409를 받는다
    @Test
    void C1_같은_좌석_동시_100명() throws Exception {
        var rounds = 3;
        var all = new ArrayList<Outcome>();
        var doubleSold = 0;
        var roundsWithoutSingleWinner = 0;
        for (var round = 0; round < rounds; round++) {
            var scheduleId = fixture.createOnSaleSchedule();
            var seat = fixture.createSeats(scheduleId, 1);
            var tasks = new ArrayList<Callable<String>>();
            for (var i = 0; i < 100; i++) {
                tasks.add(holdTask(BASE_USER + i, UUID.randomUUID(), scheduleId, seat));
            }
            var outcomes = runTogether(tasks);
            all.addAll(outcomes);
            doubleSold += fixture.doubleSoldSeats(scheduleId);
            if (countByResult(outcomes).getOrDefault(OK, 0L) != 1L) {
                roundsWithoutSingleWinner++;
            }
        }
        report("C1", rounds, all, Map.of("doubleSold", doubleSold, "roundsWithoutSingleWinner",
                roundsWithoutSingleWinner));

        assertEquals(0, doubleSold);
        assertEquals(0, roundsWithoutSingleWinner);
        assertEquals((long) rounds * 99, countByResult(all).getOrDefault("SEAT_ALREADY_HELD", 0L));
    }

    // C2(TS-1): 좌석 4개를 사용자마다 다른 순서(정순·역순·무작위)로 동시에 요청한다. 데드락·5xx가 없어야 한다
    @Test
    void C2_역순_교차_좌석_동시_요청() throws Exception {
        var rounds = 50;
        var random = new Random(79);
        var all = new ArrayList<Outcome>();
        var deadlocksBefore = fixture.deadlocks();
        var doubleSold = 0;
        for (var round = 0; round < rounds; round++) {
            var scheduleId = fixture.createOnSaleSchedule();
            var seats = fixture.createSeats(scheduleId, 4);
            var tasks = new ArrayList<Callable<String>>();
            tasks.add(holdTask(BASE_USER, UUID.randomUUID(), scheduleId, seats));
            tasks.add(holdTask(BASE_USER + 1, UUID.randomUUID(), scheduleId, seats.reversed()));
            for (var i = 2; i < 10; i++) {
                var shuffled = new ArrayList<>(seats);
                Collections.shuffle(shuffled, random);
                tasks.add(holdTask(BASE_USER + i, UUID.randomUUID(), scheduleId, shuffled.subList(0, 2)));
            }
            all.addAll(runTogether(tasks));
            doubleSold += fixture.doubleSoldSeats(scheduleId);
        }
        var deadlocks = fixture.deadlocks() - deadlocksBefore;
        report("C2", rounds, all, Map.of("deadlocks", deadlocks, "doubleSold", doubleSold));

        assertEquals(0, deadlocks);
        assertEquals(0, doubleSold);
        assertTrue(all.stream().noneMatch(Outcome::isError), () -> "5xx: " + countByResult(all));
    }

    // C3(TS-1): 다른 트랜잭션이 좌석 행 잠금을 2초 쥐고 있으면, 선점 요청은 잠금을 끝까지 기다리지 않고
    // 409 SEAT_ALREADY_HELD로 빨리 실패해야 한다(SA 9-4 lock_timeout 500ms)
    @Test
    void C3_좌석_잠금_대기_빠른_실패() throws Exception {
        var rounds = 3;
        var holdLockMillis = 2_000L;
        var all = new ArrayList<Outcome>();
        var roundMaxMillis = new ArrayList<Long>();
        for (var round = 0; round < rounds; round++) {
            var scheduleId = fixture.createOnSaleSchedule();
            var seat = fixture.createSeats(scheduleId, 1);
            var locked = new CountDownLatch(1);
            var locker = new Thread(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM schedule_seats WHERE id = ? FOR UPDATE", Long.class, seat.get(0));
                locked.countDown();
                sleep(holdLockMillis);
                status.setRollbackOnly();
            }));
            locker.start();
            assertTrue(locked.await(5, TimeUnit.SECONDS));

            // Note: 요청 수를 테스트 커넥션 풀(10)보다 충분히 작게 둔다. 잠금을 쥔 스레드도 커넥션 하나를 쓰므로 10개를 보내면
            // 일부가 커넥션을 기다려 잠금 대기와 커넥션 대기가 섞인다(2026-11-03 S3 첫 측정: max 1,792ms). 커넥션 대기는 부하 시나리오에서 본다
            var tasks = new ArrayList<Callable<String>>();
            for (var i = 0; i < 5; i++) {
                tasks.add(holdTask(BASE_USER + i, UUID.randomUUID(), scheduleId, seat));
            }
            var outcomes = runTogether(tasks);
            roundMaxMillis.add(ConcurrentRunner.percentileMillis(outcomes, 100));
            all.addAll(outcomes);
            locker.join();
        }
        report("C3", rounds, all, Map.of("holdLockMillis", holdLockMillis, "roundMaxMillis", roundMaxMillis));

        assertTrue(all.stream().noneMatch(Outcome::isError), () -> "5xx: " + countByResult(all));
        // Note: 목표는 lock_timeout의 3배인 1,500ms다. lock_timeout은 문장이 아니라 잠금 시도마다 걸린다. 같은 행을 여럿이 기다리면
        // 뒤 요청은 튜플 잠금과 행을 쥔 트랜잭션을 차례로 기다려 약 2배(1초)까지 걸린다(실측 최대 1,060~1,305ms).
        // 1,500ms는 그 상한에 여유를 더한 값이고, 잠금을 끝까지 기다린 기준선(2초 이상)과는 갈린다(concurrency-results.md 4-3절)
        assertTrue(ConcurrentRunner.percentileMillis(all, 100) < 1_500, "잠금을 1.5초 넘게 기다렸다");
        assertEquals((long) all.size(), countByResult(all).getOrDefault("SEAT_ALREADY_HELD", 0L));
    }

    // C4(TS-2, D1·E3·E16): 같은 사용자가 탭 4개에서 서로 다른 좌석 2개씩 동시에 선점한다. 첫 라운드부터 매수 행이 없는 상태다.
    // 합계 4매를 넘지 않아야 하고, 보유 매수와 활성 예매 좌석 수가 같아야 한다
    @Test
    void C4_같은_사용자_멀티_탭_매수_한도() throws Exception {
        var rounds = 30;
        var all = new ArrayList<Outcome>();
        var overLimitRounds = 0;
        var maxActive = 0;
        var quotaMismatches = 0;
        for (var round = 0; round < rounds; round++) {
            var scheduleId = fixture.createOnSaleSchedule();
            var seats = fixture.createSeats(scheduleId, 8);
            var tasks = new ArrayList<Callable<String>>();
            for (var tab = 0; tab < 4; tab++) {
                tasks.add(holdTask(BASE_USER, UUID.randomUUID(), scheduleId, seats.subList(tab * 2, tab * 2 + 2)));
            }
            all.addAll(runTogether(tasks));
            var active = fixture.activeSeatsOfUser(BASE_USER, scheduleId);
            maxActive = Math.max(maxActive, active);
            if (active > MAX_PER_USER) {
                overLimitRounds++;
            }
            if (fixture.heldCount(BASE_USER, scheduleId) != active) {
                quotaMismatches++;
            }
        }
        report("C4", rounds, all, Map.of("overLimitRounds", overLimitRounds, "maxActiveSeats", maxActive,
                "quotaMismatches", quotaMismatches));

        assertEquals(0, overLimitRounds);
        assertEquals(0, quotaMismatches);
        assertTrue(all.stream().noneMatch(Outcome::isError), () -> "5xx: " + countByResult(all));
    }

    // C5(TS-3, E2): 같은 Idempotency-Key·같은 본문으로 따닥 연타한다. 모두 같은 선점 결과(201)를 받고 예매는 1건이어야 한다
    @Test
    void C5_같은_멱등키_동시_연타() throws Exception {
        var rounds = 30;
        var all = new ArrayList<Outcome>();
        var duplicatedReservations = 0;
        for (var round = 0; round < rounds; round++) {
            var scheduleId = fixture.createOnSaleSchedule();
            var seats = fixture.createSeats(scheduleId, 2);
            var key = UUID.randomUUID();
            var tasks = new ArrayList<Callable<String>>();
            for (var click = 0; click < 3; click++) {
                tasks.add(holdTask(BASE_USER, key, scheduleId, seats));
            }
            all.addAll(runTogether(tasks));
            if (fixture.reservationsOfRequest(key) != 1) {
                duplicatedReservations++;
            }
        }
        report("C5", rounds, all, Map.of("roundsWithoutSingleReservation", duplicatedReservations));

        assertEquals(0, duplicatedReservations);
        assertEquals((long) all.size(), countByResult(all).getOrDefault(OK, 0L), () -> "결과: " + countByResult(all));
    }

    // C6(TS-4): 만료 스케줄러 두 인스턴스가 같은 대상을 동시에 처리한다. 모두 한 번씩만 만료되고,
    // 다른 인스턴스가 이미 처리한 대상을 다시 집는 헛수고(wasted)가 없어야 한다
    @Test
    void C6_만료_스케줄러_두_인스턴스() throws Exception {
        var targets = 100;
        var scheduleId = fixture.createOnSaleSchedule();
        var seats = fixture.createSeats(scheduleId, targets);
        for (var i = 0; i < targets; i++) {
            seatHold.hold(new HoldSeatsCommand(BASE_USER + i, UUID.randomUUID(), scheduleId, List.of(seats.get(i))));
        }
        fixture.passHoldExpiry(scheduleId);
        var deadlocksBefore = fixture.deadlocks();
        var expired = new AtomicInteger();
        var wasted = new AtomicInteger();
        var errors = new AtomicInteger();

        // S4부터 만료 처리 API가 건별 집기(expireNext)로 바뀌어 구동부를 바꿨다. 헛수고의 정의(집었지만 만료하지 않은 시도)는 같다
        Callable<String> instance = () -> {
            while (true) {
                try {
                    var attempt = expiry.expireNext(java.util.Set.of());
                    if (attempt.isEmpty()) {
                        return OK;
                    }
                    if (attempt.get().expired()) {
                        expired.incrementAndGet();
                    } else {
                        wasted.incrementAndGet();
                    }
                } catch (RuntimeException exception) {
                    errors.incrementAndGet();
                    return "ERROR:" + exception.getClass().getSimpleName();
                }
            }
        };
        var outcomes = runTogether(List.of(instance, instance));
        var deadlocks = fixture.deadlocks() - deadlocksBefore;
        var mismatches = fixture.seatStatusMismatches(scheduleId);
        report("C6", 1, outcomes, Map.of("expired", expired.get(), "wasted", wasted.get(), "errors", errors.get(),
                "deadlocks", deadlocks, "seatMismatches", mismatches));

        assertEquals(targets, expired.get());
        assertEquals(0, errors.get());
        assertEquals(0, deadlocks);
        assertEquals(0, mismatches);
        assertEquals(0, wasted.get(), "다른 인스턴스가 처리한 대상을 다시 집었다");
    }

    // C7(TS-4, 8-4 잠금 순서): 사용자 A의 만료 대상 예매를 회수하는 동안 A가 새 좌석을, B가 회수 중인 좌석을 동시에 선점한다.
    // 만료(예매 → 좌석 → 매수)와 선점(매수 → 좌석)의 잠금 순서가 반대라 교착이 생기는지 본다
    @Test
    void C7_만료와_같은_사용자_선점_경합() throws Exception {
        var rounds = 30;
        var userA = BASE_USER;
        var userB = BASE_USER + 1;
        var all = new ArrayList<Outcome>();
        var deadlocksBefore = fixture.deadlocks();
        var invariantBroken = 0;
        for (var round = 0; round < rounds; round++) {
            var scheduleId = fixture.createOnSaleSchedule();
            var seats = fixture.createSeats(scheduleId, 2);
            var expiring = ((SyncHoldOutcome.Held) seatHold.hold(new HoldSeatsCommand(userA, UUID.randomUUID(), scheduleId,
                    List.of(seats.get(0))))).result().reservationId();
            fixture.passHoldExpiry(scheduleId);

            all.addAll(runTogether(List.of(
                    () -> expiry.expire(expiring) ? OK : "SKIPPED",
                    holdTask(userA, UUID.randomUUID(), scheduleId, List.of(seats.get(1))),
                    holdTask(userB, UUID.randomUUID(), scheduleId, List.of(seats.get(0))))));

            if (fixture.doubleSoldSeats(scheduleId) != 0 || fixture.seatStatusMismatches(scheduleId) != 0
                    || fixture.heldCount(userA, scheduleId) != fixture.activeSeatsOfUser(userA, scheduleId)
                    || fixture.heldCount(userB, scheduleId) != fixture.activeSeatsOfUser(userB, scheduleId)) {
                invariantBroken++;
            }
        }
        var deadlocks = fixture.deadlocks() - deadlocksBefore;
        report("C7", rounds, all, Map.of("deadlocks", deadlocks, "invariantBrokenRounds", invariantBroken));

        assertEquals(0, deadlocks);
        assertEquals(0, invariantBroken);
        assertTrue(all.stream().noneMatch(Outcome::isError), () -> "5xx: " + countByResult(all));
    }

    private Callable<String> holdTask(long userId, UUID key, Long scheduleId, List<Long> seatIds) {
        var command = new HoldSeatsCommand(userId, key, scheduleId, List.copyOf(seatIds));
        return () -> {
            seatHold.hold(command);
            return OK;
        };
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
