package com.iseonjwa.reservation.reservation.infrastructure.persistence;

import com.iseonjwa.reservation.reservation.domain.entity.ReservationSeat;
import com.iseonjwa.reservation.reservation.domain.repository.ActiveSeatConflictException;
import com.iseonjwa.reservation.reservation.domain.repository.ReservationSeatRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

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
