package com.iseonjwa.reservation.reservation.infrastructure.persistence;

import com.iseonjwa.reservation.global.vo.Money;
import com.iseonjwa.reservation.reservation.domain.entity.ReservationSeat;
import com.iseonjwa.reservation.reservation.domain.repository.ActiveSeatConflictException;
import com.iseonjwa.reservation.reservation.domain.repository.ReservationSeatRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Repository
public class JpaReservationSeatRepository implements ReservationSeatRepository {
    private static final String ACTIVE_SEAT_CONSTRAINT = "uq_reservation_seats_active_schedule_seat";

    private final SpringDataReservationSeatRepository repository;

    public JpaReservationSeatRepository(SpringDataReservationSeatRepository repository) {
        this.repository = repository;
    }

    @Override
    public ReservationSeat save(ReservationSeat seat) {
        return repository.saveAndFlush(seat);
    }

    @Override
    public List<ReservationSeat> saveAll(List<ReservationSeat> seats) {
        try {
            return repository.saveAllAndFlush(seats);
        } catch (DataIntegrityViolationException exception) {
            if (violates(exception, ACTIVE_SEAT_CONSTRAINT)) {
                throw new ActiveSeatConflictException(exception);
            }
            throw exception;
        }
    }

    @Override
    public List<ReservationSeat> findByReservationId(Long reservationId) {
        return repository.findByReservationIdOrderByIdAsc(reservationId);
    }

    @Override
    public int releaseHeld(Long reservationId, Instant now, Long actorId) {
        return repository.releaseHeld(reservationId, now, actorId);
    }

    @Override
    public int confirmHeld(Long reservationId, Instant now) {
        return repository.confirmHeld(reservationId, now);
    }

    @Override
    public List<SeatDetailRow> findSeatDetails(Long reservationId) {
        return repository.findSeatDetailRows(reservationId).stream()
                .map(row -> new SeatDetailRow(((Number) row[0]).longValue(), (String) row[1],
                        ((Number) row[2]).intValue(), ((Number) row[3]).intValue(), (String) row[4],
                        Money.of((BigDecimal) row[5]), (String) row[6]))
                .toList();
    }

    // 다른 제약(FK 등) 위반은 좌석 경합이 아니라 버그이므로 그대로 올려 보낸다
    private static boolean violates(Throwable exception, String constraintName) {
        for (var cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return constraintName.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        return false;
    }
}
