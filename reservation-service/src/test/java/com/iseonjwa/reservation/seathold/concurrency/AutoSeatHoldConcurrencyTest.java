package com.iseonjwa.reservation.seathold.concurrency;

import com.iseonjwa.reservation.seathold.application.AutoHoldSeatsCommand;
import com.iseonjwa.reservation.seathold.application.SyncHoldOutcome;
import com.iseonjwa.reservation.seathold.application.SyncSeatHoldService;
import com.iseonjwa.reservation.seathold.concurrency.ConcurrentRunner.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.iseonjwa.reservation.seathold.concurrency.ConcurrentRunner.OK;
import static com.iseonjwa.reservation.seathold.concurrency.ConcurrentRunner.countByResult;
import static com.iseonjwa.reservation.seathold.concurrency.ConcurrentRunner.report;
import static com.iseonjwa.reservation.seathold.concurrency.ConcurrentRunner.runTogether;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 자동 배정 선점의 동시성(ISE-264). 같은 구역에 여러 요청이 동시에 와도 좌석을 두 번 주지 않고, 남은 좌석이 있는 동안은
 * 서로 기다리거나 충돌하지 않으며, 매수 한도와 멱등키를 지키는지 본다.
 *
 * <p>Note: C1~C7({@link SeatHoldConcurrencyTest})과 달리 기본 빌드에서 돈다. 스레드 30개 한 라운드라 몇 초면 끝나고,
 * SKIP LOCKED·매수 한도·멱등 비교를 빼면 실패해야 하는 회귀 테스트이기 때문이다. 결과는 확률적이라 라운드를 반복한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class AutoSeatHoldConcurrencyTest {
    private static final long BASE_USER = 2_640_000L;
    private static final int USERS = 30;
    private static final int ROUNDS = 3;

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SyncSeatHoldService seatHold;

    private SeatHoldFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new SeatHoldFixture(jdbc);
    }

    @AfterEach
    void cleanUp() {
        fixture.cleanUp();
    }

    // 좌석이 넉넉하면 모두 성공한다. 잠금을 빼면 같은 앞 좌석을 골라 409가 나고, SKIP LOCKED를 빼면 줄을 서서 기다린다
    @Test
    void 같은_구역_동시_30명은_좌석이_남는_동안_모두_서로_다른_좌석을_받는다() throws Exception {
        var all = new ArrayList<Outcome>();
        var doubleSold = 0;
        var mismatches = 0;
        for (var round = 0; round < ROUNDS; round++) {
            var scheduleId = fixture.createOnSaleSchedule();
            fixture.createSeatsInBulk(scheduleId, USERS * 2);
            var tasks = new ArrayList<Callable<String>>();
            for (var i = 0; i < USERS; i++) {
                tasks.add(autoTask(BASE_USER + i, UUID.randomUUID(), scheduleId, 1 + i % 2, null));
            }
            all.addAll(runTogether(tasks));
            doubleSold += fixture.doubleSoldSeats(scheduleId);
            mismatches += fixture.seatStatusMismatches(scheduleId);
        }
        report("AUTO-1", ROUNDS, all, Map.of("doubleSold", doubleSold, "mismatches", mismatches));

        assertEquals(0, doubleSold);
        assertEquals(0, mismatches);
        assertEquals(Map.of(OK, (long) ROUNDS * USERS), countByResult(all));
    }

    // 좌석 20석에 30명이 1석씩 → 정확히 20명 성공, 10명은 409 ZONE_SOLD_OUT, 중복 판매 0
    @Test
    void 남은_좌석보다_많이_오면_남은_만큼만_성공하고_나머지는_구역_매진이다() throws Exception {
        var all = new ArrayList<Outcome>();
        var doubleSold = 0;
        var heldSeats = 0;
        for (var round = 0; round < ROUNDS; round++) {
            var scheduleId = fixture.createOnSaleSchedule();
            fixture.createSeatsInBulk(scheduleId, 20);
            var tasks = new ArrayList<Callable<String>>();
            for (var i = 0; i < USERS; i++) {
                tasks.add(autoTask(BASE_USER + i, UUID.randomUUID(), scheduleId, 1, null));
            }
            all.addAll(runTogether(tasks));
            doubleSold += fixture.doubleSoldSeats(scheduleId);
            heldSeats += jdbc.queryForObject("SELECT count(*) FROM schedule_seats WHERE schedule_id = ?"
                    + " AND status = 'HELD'", Integer.class, scheduleId);
        }
        report("AUTO-2", ROUNDS, all, Map.of("doubleSold", doubleSold, "heldSeats", heldSeats));

        assertEquals(0, doubleSold);
        assertEquals(ROUNDS * 20, heldSeats);
        assertEquals(Map.of(OK, (long) ROUNDS * 20, "ZONE_SOLD_OUT", (long) ROUNDS * 10), countByResult(all));
    }

    // 한 사용자가 다른 키로 2석씩 10번 동시에 → 한도 4석이라 2번만 성공한다
    @Test
    void 한_사용자의_동시_요청은_매수_한도를_넘지_않는다() throws Exception {
        var all = new ArrayList<Outcome>();
        for (var round = 0; round < ROUNDS; round++) {
            var scheduleId = fixture.createOnSaleSchedule();
            fixture.createSeatsInBulk(scheduleId, 40);
            var tasks = new ArrayList<Callable<String>>();
            for (var i = 0; i < 10; i++) {
                tasks.add(autoTask(BASE_USER, UUID.randomUUID(), scheduleId, 2, null));
            }
            var outcomes = runTogether(tasks);
            all.addAll(outcomes);
            assertEquals(2L, countByResult(outcomes).getOrDefault(OK, 0L), countByResult(outcomes)::toString);
            assertEquals(4, fixture.heldCount(BASE_USER, scheduleId));
            assertEquals(4, fixture.activeSeatsOfUser(BASE_USER, scheduleId));
        }
        report("AUTO-3", ROUNDS, all, Map.of());

        // 진 요청은 한도 초과다. 매수 행을 500ms 넘게 기다리면 잠금 시간 초과(SEAT_ALREADY_HELD)일 수 있다
        assertTrue(countByResult(all).keySet().stream()
                .allMatch(Set.of(OK, "PURCHASE_LIMIT_EXCEEDED", "SEAT_ALREADY_HELD")::contains),
                countByResult(all)::toString);
    }

    // 같은 멱등키 연타 10번 → 예매 1건, 모두 같은 좌석으로 성공한다
    @Test
    void 같은_멱등키_동시_요청은_한_번만_선점하고_같은_좌석을_돌려준다() throws Exception {
        for (var round = 0; round < ROUNDS; round++) {
            var scheduleId = fixture.createOnSaleSchedule();
            fixture.createSeatsInBulk(scheduleId, 10);
            var key = UUID.randomUUID();
            var seatLists = new ConcurrentLinkedQueue<List<Long>>();
            var tasks = new ArrayList<Callable<String>>();
            for (var i = 0; i < 10; i++) {
                tasks.add(autoTask(BASE_USER, key, scheduleId, 2, seatLists));
            }
            var outcomes = runTogether(tasks);

            assertEquals(Map.of(OK, 10L), countByResult(outcomes));
            assertEquals(1, fixture.reservationsOfRequest(key));
            assertEquals(1, Set.copyOf(seatLists).size(), seatLists::toString);
            assertEquals(2, fixture.heldCount(BASE_USER, scheduleId));
        }
    }

    private Callable<String> autoTask(long userId, UUID key, Long scheduleId, int quantity,
            ConcurrentLinkedQueue<List<Long>> seatLists) {
        return () -> {
            var outcome = seatHold.holdAuto(new AutoHoldSeatsCommand(userId, key, scheduleId, null, "A", quantity));
            if (outcome instanceof SyncHoldOutcome.Held held && seatLists != null) {
                seatLists.add(held.result().seatIds());
            }
            return OK;
        };
    }
}
