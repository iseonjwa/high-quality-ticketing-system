package com.iseonjwa.reservation.reservation.domain.entity;

import com.iseonjwa.reservation.global.vo.Money;
import com.iseonjwa.reservation.reservation.domain.repository.ReservationRepository;
import com.iseonjwa.reservation.reservation.domain.repository.ReservationSeatRepository;
import com.iseonjwa.reservation.reservation.domain.repository.ReservationStatusHistoryRepository;
import com.iseonjwa.reservation.reservation.domain.repository.UserScheduleQuotaRepository;
import com.iseonjwa.reservation.seathold.domain.entity.SeatHoldRequest;
import com.iseonjwa.reservation.seathold.domain.repository.SeatHoldRequestRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Note: 제약 위반 뒤에는 PostgreSQL이 트랜잭션을 중단 상태로 만든다.
// 테스트마다 위반을 하나만 확인하고, 클래스 전체 롤백으로 데이터를 남기지 않는다.
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
@Transactional
class SeatHoldMigrationTest {
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    EntityManager em;
    @Autowired
    SeatHoldRequestRepository seatHoldRequestRepository;
    @Autowired
    ReservationRepository reservationRepository;
    @Autowired
    ReservationSeatRepository reservationSeatRepository;
    @Autowired
    ReservationStatusHistoryRepository reservationStatusHistoryRepository;
    @Autowired
    UserScheduleQuotaRepository userScheduleQuotaRepository;

    private Long scheduleId;
    private Long scheduleSeatId;

    @BeforeEach
    void setUp() {
        Long venueId = jdbc.queryForObject(
                "INSERT INTO venues (name, created_at, updated_at) VALUES ('KSPO DOME', now(), now()) RETURNING id",
                Long.class);
        Long venueSeatId = jdbc.queryForObject(
                "INSERT INTO venue_seats (venue_id, section, row_no, seat_no, grade, created_at, updated_at)"
                        + " VALUES (?, 'A', '1', 1, 'VIP', now(), now()) RETURNING id",
                Long.class, venueId);
        Long concertId = jdbc.queryForObject(
                "INSERT INTO concerts (title, version, created_at, updated_at)"
                        + " VALUES ('콘서트', 0, now(), now()) RETURNING id",
                Long.class);
        scheduleId = jdbc.queryForObject(
                "INSERT INTO schedules (concert_id, venue_id, starts_at, open_at, version, created_at, updated_at)"
                        + " VALUES (?, ?, now() + interval '2 day', now() + interval '1 day', 0, now(), now())"
                        + " RETURNING id",
                Long.class, concertId, venueId);
        scheduleSeatId = jdbc.queryForObject(
                "INSERT INTO schedule_seats (schedule_id, venue_seat_id, section, grade, price, status, created_at, updated_at)"
                        + " VALUES (?, ?, 'A', 'VIP', 165000, 'AVAILABLE', now(), now()) RETURNING id",
                Long.class, scheduleId, venueSeatId);
    }

    @Test
    void 좌석_선점과_예매_제약_및_인덱스가_카탈로그에_존재한다() {
        var reservationConstraints = names("SELECT conname FROM pg_constraint WHERE conrelid = 'reservations'::regclass");
        assertTrue(reservationConstraints.containsAll(List.of(
                "uq_reservations_reservation_no", "uq_reservations_request_id",
                "chk_reservations_status", "chk_reservations_source")),
                "누락된 예매 제약이 있다: " + reservationConstraints);

        var reservationIndexes = names("SELECT indexname FROM pg_indexes WHERE tablename = 'reservations'");
        assertTrue(reservationIndexes.containsAll(List.of(
                "idx_reservations_status_hold_expires_at", "idx_reservations_user_id_created_at")),
                "누락된 예매 인덱스가 있다: " + reservationIndexes);

        var seatIndexes = names("SELECT indexname FROM pg_indexes WHERE tablename = 'reservation_seats'");
        assertTrue(seatIndexes.containsAll(List.of(
                "uq_reservation_seats_active_schedule_seat", "idx_reservation_seats_reservation_id")),
                "누락된 예매 좌석 인덱스가 있다: " + seatIndexes);
    }

    @Test
    void 같은_회차_좌석에_HELD_예매_좌석을_두_건_만들_수_없다() {
        Long firstReservationId = insertReservation("R-20261030-0001");
        Long secondReservationId = insertReservation("R-20261030-0002");

        insertReservationSeat(firstReservationId, scheduleSeatId, "HELD");

        assertViolation("uq_reservation_seats_active_schedule_seat",
                () -> insertReservationSeat(secondReservationId, scheduleSeatId, "HELD"));
    }

    @Test
    void 같은_회차_좌석에_HELD와_CONFIRMED_예매_좌석이_함께_있을_수_없다() {
        Long firstReservationId = insertReservation("R-20261030-0001");
        Long secondReservationId = insertReservation("R-20261030-0002");

        insertReservationSeat(firstReservationId, scheduleSeatId, "HELD");

        assertViolation("uq_reservation_seats_active_schedule_seat",
                () -> insertReservationSeat(secondReservationId, scheduleSeatId, "CONFIRMED"));
    }

    @Test
    void 기존_예매_좌석이_RELEASED면_같은_회차_좌석에_새_HELD_행_생성이_가능하다() {
        Long releasedReservationId = insertReservation("R-20261030-0003");
        Long newReservationId = insertReservation("R-20261030-0004");

        insertReservationSeat(releasedReservationId, scheduleSeatId, "RELEASED");
        insertReservationSeat(newReservationId, scheduleSeatId, "HELD");

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM reservation_seats WHERE schedule_seat_id = ?",
                Integer.class, scheduleSeatId);
        assertEquals(2, count);
    }

    @Test
    void 보유_매수는_음수가_될_수_없다() {
        assertViolation("chk_user_schedule_quotas_held_count", () -> jdbc.update("""
                INSERT INTO user_schedule_quotas (user_id, schedule_id, held_count, created_at, updated_at)
                VALUES (1, ?, -1, now(), now())""", scheduleId));
    }

    @Test
    void 같은_request_id로_선점_요청을_두_번_저장하면_merge되지_않고_PK_충돌이_난다() {
        var requestId = UUID.randomUUID();
        seatHoldRequestRepository.save(SeatHoldRequest.request(requestId, 7L, scheduleId, List.of(scheduleSeatId)));
        em.clear();

        assertViolation("seat_hold_requests_pkey", () -> seatHoldRequestRepository.save(
                SeatHoldRequest.request(requestId, 7L, scheduleId, List.of(scheduleSeatId))));
    }

    @Test
    void 저장소로_선점_요청과_예매를_저장하고_보유_매수를_읽을_수_있다() {
        var requestId = UUID.randomUUID();
        seatHoldRequestRepository.save(SeatHoldRequest.request(requestId, 7L, scheduleId, List.of(scheduleSeatId)));

        var reservation = reservationRepository.save(Reservation.hold(
                "R-20261030-0005",
                requestId,
                7L,
                scheduleId,
                List.of(Money.of(165_000)),
                Instant.now().plusSeconds(300)));

        reservationSeatRepository.save(ReservationSeat.hold(reservation.getId(), scheduleSeatId, Money.of(165_000)));
        reservationStatusHistoryRepository.save(ReservationStatusHistory.of(
                reservation.getId(), null, ReservationStatus.HELD, "SEAT_HELD", Instant.now()));

        jdbc.update("""
                INSERT INTO user_schedule_quotas (user_id, schedule_id, held_count, created_at, updated_at)
                VALUES (7, ?, 1, now(), now())""", scheduleId);
        em.clear();

        var loadedRequest = seatHoldRequestRepository.findById(requestId).orElseThrow();
        var loadedReservation = reservationRepository.findByRequestId(requestId).orElseThrow();
        var loadedSeats = reservationSeatRepository.findByReservationId(reservation.getId());
        var loadedHistories = reservationStatusHistoryRepository.findByReservationId(reservation.getId());
        var loadedQuota = userScheduleQuotaRepository.findById(new UserScheduleQuotaId(7L, scheduleId)).orElseThrow();

        assertFalse(loadedRequest.isNew());
        assertEquals(List.of(scheduleSeatId), loadedRequest.getSeatIds());
        assertEquals(reservation.getId(), loadedReservation.getId());
        assertEquals(1, loadedSeats.size());
        assertEquals(1, loadedHistories.size());
        assertEquals(1, loadedQuota.getHeldCount());
    }

    private Long insertReservation(String reservationNo) {
        return jdbc.queryForObject("""
                INSERT INTO reservations (reservation_no, request_id, user_id, schedule_id, status,
                                          quantity, total_amount, hold_expires_at, source, created_at, updated_at)
                VALUES (?, ?, 1, ?, 'HELD', 1, 165000, now() + interval '5 minute', 'GENERAL', now(), now())
                RETURNING id""",
                Long.class, reservationNo, UUID.randomUUID(), scheduleId);
    }

    private void insertReservationSeat(Long reservationId, Long seatId, String status) {
        jdbc.update("""
                INSERT INTO reservation_seats (reservation_id, schedule_seat_id, price, status, created_at, updated_at)
                VALUES (?, ?, 165000, ?, now(), now())""",
                reservationId, seatId, status);
    }

    private List<String> names(String sql) {
        return jdbc.queryForList(sql, String.class);
    }

    private static void assertViolation(String constraint, Runnable action) {
        var exception = assertThrows(DataIntegrityViolationException.class, action::run);
        assertTrue(exception.getMessage().contains(constraint), "기대한 제약 " + constraint + ": " + exception.getMessage());
    }
}
