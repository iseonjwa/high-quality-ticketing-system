package com.iseonjwa.reservation.seathold.application;

import com.iseonjwa.reservation.seathold.application.SeatLedgerReconciliationService.LedgerRepair;
import com.iseonjwa.reservation.seathold.domain.repository.SeatLedgerRepository.HeldCountMismatch;
import com.iseonjwa.reservation.seathold.domain.repository.SeatLedgerRepository.SeatStatusMismatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 좌석 원장 대사(ISE-14)를 실제 PostgreSQL로 확인한다. 어긋남은 JDBC로 회차 좌석·보유 매수를 직접 바꿔 만든다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class SeatLedgerReconciliationTest {
    private static final String MARK = "ISE-14 대사 테스트";
    private static final long USER = 52L;
    private static final long OTHER_USER = 53L;
    private static final long SEAT_PRICE = 165000L;

    private final List<Long> createdVenueIds = new ArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SyncSeatHoldService seatHold;
    @Autowired
    SeatHoldExpiryService expiry;
    @Autowired
    SeatLedgerReconciliationService reconciliation;

    private Long scheduleId;
    private List<Long> seatIds;

    @BeforeEach
    void setUp() {
        scheduleId = createSchedule();
        seatIds = createSeats(scheduleId, 6);
    }

    @AfterEach
    void cleanUp() {
        for (var venueId : createdVenueIds) {
            var scheduleIds = "(SELECT id FROM schedules WHERE venue_id = ?)";
            var reservationIds = "(SELECT id FROM reservations WHERE schedule_id IN " + scheduleIds + ")";
            jdbc.update("DELETE FROM seat_hold_requests WHERE schedule_id IN " + scheduleIds, venueId);
            jdbc.update("DELETE FROM reservation_status_histories WHERE reservation_id IN " + reservationIds, venueId);
            jdbc.update("DELETE FROM reservation_seats WHERE reservation_id IN " + reservationIds, venueId);
            jdbc.update("DELETE FROM reservations WHERE schedule_id IN " + scheduleIds, venueId);
            jdbc.update("DELETE FROM user_schedule_quotas WHERE schedule_id IN " + scheduleIds, venueId);
            jdbc.update("DELETE FROM schedule_seats WHERE schedule_id IN " + scheduleIds, venueId);
            jdbc.update("DELETE FROM schedules WHERE venue_id = ?", venueId);
            jdbc.update("DELETE FROM venue_seats WHERE venue_id = ?", venueId);
            jdbc.update("DELETE FROM venues WHERE id = ?", venueId);
        }
        createdVenueIds.clear();
        jdbc.update("DELETE FROM concerts WHERE title = ? AND id NOT IN (SELECT concert_id FROM schedules)", MARK);
    }

    @Test
    void 선점과_확정과_만료를_거친_원장은_어긋남이_없다() {
        hold(USER, List.of(seatIds.get(0), seatIds.get(1)));
        var confirmedId = hold(OTHER_USER, List.of(seatIds.get(2)));
        confirm(confirmedId);
        var expiredId = hold(OTHER_USER, List.of(seatIds.get(3)));
        passHoldExpiry(expiredId);
        assertTrue(expiry.expire(expiredId));

        var inspection = reconciliation.inspect(scheduleId);

        assertTrue(inspection.consistent());
    }

    @Test
    void 원장과_다른_회차_좌석_상태를_찾아_원장_기준으로_고친다() {
        var heldId = hold(USER, List.of(seatIds.get(0)));
        var confirmedId = hold(OTHER_USER, List.of(seatIds.get(1)));
        confirm(confirmedId);
        // 선점은 살아 있는데 좌석이 풀림, 확정인데 HELD로 남음, 원장이 없는데 HELD·SOLD로 남음
        setSeatStatus(seatIds.get(0), "AVAILABLE");
        setSeatStatus(seatIds.get(1), "HELD");
        setSeatStatus(seatIds.get(4), "HELD");
        setSeatStatus(seatIds.get(5), "SOLD");

        assertEquals(List.of(
                new SeatStatusMismatch(seatIds.get(0), "AVAILABLE", "HELD"),
                new SeatStatusMismatch(seatIds.get(1), "HELD", "SOLD"),
                new SeatStatusMismatch(seatIds.get(4), "HELD", "AVAILABLE"),
                new SeatStatusMismatch(seatIds.get(5), "SOLD", "AVAILABLE")),
                reconciliation.inspect(scheduleId).seatMismatches());

        var repair = reconciliation.reconcile(scheduleId);

        assertEquals(4, repair.seatMismatches());
        assertEquals(4, repair.repairedSeats());
        assertTrue(repair.remaining().consistent());
        assertEquals(List.of("HELD", "SOLD", "AVAILABLE", "AVAILABLE", "AVAILABLE", "AVAILABLE"), seatStatuses());
        assertEquals("HELD", reservationStatus(heldId));
    }

    @Test
    void 원장과_다른_보유_매수를_찾아_활성_예매_좌석_수로_고친다() {
        hold(USER, List.of(seatIds.get(0), seatIds.get(1)));
        var confirmedId = hold(OTHER_USER, List.of(seatIds.get(2)));
        confirm(confirmedId);
        // 확정 예매도 매수 한도에 들어가므로 CONFIRMED도 센다. 원장 없는 사용자의 매수와 빠진 매수 행도 만든다
        setHeldCount(USER, 3);
        jdbc.update("DELETE FROM user_schedule_quotas WHERE user_id = ? AND schedule_id = ?", OTHER_USER, scheduleId);
        insertHeldCount(99L, 2);

        assertEquals(List.of(
                new HeldCountMismatch(USER, 3, 2),
                new HeldCountMismatch(OTHER_USER, 0, 1),
                new HeldCountMismatch(99L, 2, 0)),
                reconciliation.inspect(scheduleId).heldCountMismatches());

        var repair = reconciliation.reconcile(scheduleId);

        assertEquals(3, repair.heldCountMismatches());
        assertEquals(3, repair.repairedHeldCounts());
        assertTrue(repair.remaining().consistent());
        assertEquals(2, heldCount(USER));
        assertEquals(1, heldCount(OTHER_USER));
        assertEquals(0, heldCount(99L));
    }

    @Test
    void 보정한_매수로_다시_선점할_수_있다() {
        hold(USER, List.of(seatIds.get(0)));
        // 만료가 매수를 돌려주지 못한 채 끝난 것처럼 한도까지 차 있게 만든다
        setHeldCount(USER, maxPerUser());

        reconciliation.reconcile(scheduleId);
        hold(USER, List.of(seatIds.get(1)));

        assertEquals(2, heldCount(USER));
        assertTrue(reconciliation.inspect(scheduleId).consistent());
    }

    @Test
    void 대사는_다른_회차를_건드리지_않는다() {
        var otherScheduleId = createSchedule();
        var otherSeatIds = createSeats(otherScheduleId, 1);
        setSeatStatus(otherSeatIds.get(0), "HELD");

        var repair = reconciliation.reconcile(scheduleId);

        assertEquals(0, repair.repairedSeats());
        assertEquals("HELD", jdbc.queryForObject("SELECT status FROM schedule_seats WHERE id = ?", String.class,
                otherSeatIds.get(0)));
    }

    @Test
    void 선점과_만료가_도는_중에_대사를_반복해도_맞는_원장을_고치지_않는다() throws Exception {
        var running = new AtomicBoolean(true);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            var holders = new ArrayList<Future<?>>();
            for (var worker = 0; worker < 2; worker++) {
                var userId = 200L + worker;
                var seats = List.of(seatIds.get(worker * 2), seatIds.get(worker * 2 + 1));
                holders.add(pool.submit(() -> holdAndExpireRepeatedly(userId, seats, 30)));
            }
            Future<Integer> repairs = pool.submit(() -> {
                var repaired = 0;
                while (running.get()) {
                    LedgerRepair repair = reconciliation.reconcile(scheduleId);
                    repaired += repair.repairedSeats() + repair.repairedHeldCounts();
                }
                return repaired;
            });
            for (var holder : holders) {
                holder.get(60, TimeUnit.SECONDS);
            }
            running.set(false);

            assertEquals(0, repairs.get(10, TimeUnit.SECONDS));
            assertTrue(reconciliation.inspect(scheduleId).consistent());
        } finally {
            running.set(false);
            pool.shutdownNow();
        }
    }

    @Test
    void 내부_API로_어긋남을_보고_고친다() throws Exception {
        hold(USER, List.of(seatIds.get(0)));
        setSeatStatus(seatIds.get(0), "AVAILABLE");
        var base = "http://localhost:" + port + "/internal/seat-holds/schedules/" + scheduleId + "/ledger";

        var inspected = send(HttpRequest.newBuilder(URI.create(base)).GET().build());
        var repaired = send(HttpRequest.newBuilder(URI.create(base + "/reconcile"))
                .POST(HttpRequest.BodyPublishers.noBody()).build());

        assertEquals(1, inspected.path("data").path("seatMismatches").size());
        assertEquals("HELD", inspected.path("data").path("seatMismatches").path(0).path("expected").asString());
        assertEquals(1, repaired.path("data").path("repairedSeats").asInt());
        assertEquals(0, repaired.path("data").path("remaining").path("seatMismatches").size());
    }

    private JsonNode send(HttpRequest request) throws Exception {
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return JsonMapper.builder().build().readTree(response.body());
    }

    private void holdAndExpireRepeatedly(long userId, List<Long> seats, int rounds) {
        for (var round = 0; round < rounds; round++) {
            var reservationId = hold(userId, seats);
            passHoldExpiry(reservationId);
            if (!expiry.expire(reservationId)) {
                throw new IllegalStateException("만료하지 못했습니다: " + reservationId);
            }
        }
    }

    private Long hold(long userId, List<Long> seats) {
        return seatHold.hold(new HoldSeatsCommand(userId, UUID.randomUUID(), scheduleId, seats)).reservationId();
    }

    // 결제 결과 소비자의 확정을 흉내 낸다. 보유 매수는 확정 때 빼지 않는다
    private void confirm(Long reservationId) {
        jdbc.update("UPDATE reservations SET status = 'CONFIRMED' WHERE id = ?", reservationId);
        jdbc.update("UPDATE reservation_seats SET status = 'CONFIRMED' WHERE reservation_id = ?", reservationId);
        jdbc.update("UPDATE schedule_seats SET status = 'SOLD' WHERE id IN"
                + " (SELECT schedule_seat_id FROM reservation_seats WHERE reservation_id = ?)", reservationId);
    }

    // Note: 기한은 DB now()로, 비교 기준은 앱 시계로 정한다. Docker DB 시계가 호스트보다 몇 초 앞설 수 있어 넉넉히 뺀다
    private void passHoldExpiry(Long reservationId) {
        jdbc.update("UPDATE reservations SET hold_expires_at = now() - interval '1 hour' WHERE id = ?",
                reservationId);
    }

    private void setSeatStatus(Long scheduleSeatId, String status) {
        jdbc.update("UPDATE schedule_seats SET status = ? WHERE id = ?", status, scheduleSeatId);
    }

    private void setHeldCount(long userId, int heldCount) {
        jdbc.update("UPDATE user_schedule_quotas SET held_count = ? WHERE user_id = ? AND schedule_id = ?",
                heldCount, userId, scheduleId);
    }

    private void insertHeldCount(long userId, int heldCount) {
        jdbc.update("INSERT INTO user_schedule_quotas (user_id, schedule_id, held_count, created_at, updated_at)"
                + " VALUES (?, ?, ?, now(), now())", userId, scheduleId, heldCount);
    }

    private int maxPerUser() {
        return jdbc.queryForObject("SELECT max_per_user FROM schedules WHERE id = ?", Integer.class, scheduleId);
    }

    private Long createSchedule() {
        var venueId = jdbc.queryForObject("INSERT INTO venues (name, created_at, updated_at)"
                + " VALUES (?, now(), now()) RETURNING id", Long.class, MARK);
        createdVenueIds.add(venueId);
        var concertId = jdbc.queryForObject("INSERT INTO concerts (title, version, created_at, updated_at)"
                + " VALUES (?, 0, now(), now()) RETURNING id", Long.class, MARK);
        return jdbc.queryForObject("INSERT INTO schedules (concert_id, venue_id, starts_at, open_at, version,"
                + " created_at, updated_at) VALUES (?, ?, now() + interval '1 day', now() - interval '1 minute',"
                + " 0, now(), now()) RETURNING id", Long.class, concertId, venueId);
    }

    private List<Long> createSeats(Long scheduleId, int count) {
        var venueId = jdbc.queryForObject("SELECT venue_id FROM schedules WHERE id = ?", Long.class, scheduleId);
        var ids = new ArrayList<Long>();
        for (var i = 0; i < count; i++) {
            var venueSeatId = jdbc.queryForObject("INSERT INTO venue_seats (venue_id, section, row_no, seat_no, grade,"
                    + " created_at, updated_at) VALUES (?, 'A', 1, ?, 'VIP', now(), now()) RETURNING id",
                    Long.class, venueId, i + 1);
            ids.add(jdbc.queryForObject("INSERT INTO schedule_seats (schedule_id, venue_seat_id, section, grade, price,"
                    + " status, created_at, updated_at) VALUES (?, ?, 'A', 'VIP', ?, 'AVAILABLE', now(), now())"
                    + " RETURNING id", Long.class, scheduleId, venueSeatId, SEAT_PRICE));
        }
        return ids;
    }

    private String reservationStatus(Long reservationId) {
        return jdbc.queryForObject("SELECT status FROM reservations WHERE id = ?", String.class, reservationId);
    }

    private List<String> seatStatuses() {
        var statuses = new ArrayList<String>();
        for (var seatId : seatIds) {
            statuses.add(jdbc.queryForObject("SELECT status FROM schedule_seats WHERE id = ?", String.class, seatId));
        }
        return statuses;
    }

    private int heldCount(long userId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(held_count), 0) FROM user_schedule_quotas"
                + " WHERE user_id = ? AND schedule_id = ?", Integer.class, userId, scheduleId);
    }
}
