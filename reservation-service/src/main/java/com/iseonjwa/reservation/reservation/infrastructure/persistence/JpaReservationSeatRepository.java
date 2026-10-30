package com.iseonjwa.reservation.reservation.infrastructure.persistence;

import com.iseonjwa.reservation.reservation.domain.entity.ReservationSeat;
import com.iseonjwa.reservation.reservation.domain.repository.ReservationSeatRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JpaReservationSeatRepository implements ReservationSeatRepository {
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
        return repository.saveAllAndFlush(seats);
    }

    @Override
    public List<ReservationSeat> findByReservationId(Long reservationId) {
        return repository.findByReservationIdOrderByIdAsc(reservationId);
    }
}
