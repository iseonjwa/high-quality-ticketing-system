package com.iseonjwa.reservation.reservation.infrastructure.persistence;

import com.iseonjwa.reservation.reservation.domain.entity.ReservationStatusHistory;
import com.iseonjwa.reservation.reservation.domain.repository.ReservationStatusHistoryRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JpaReservationStatusHistoryRepository implements ReservationStatusHistoryRepository {
    private final SpringDataReservationStatusHistoryRepository repository;

    public JpaReservationStatusHistoryRepository(SpringDataReservationStatusHistoryRepository repository) {
        this.repository = repository;
    }

    @Override
    public ReservationStatusHistory save(ReservationStatusHistory history) {
        return repository.saveAndFlush(history);
    }

    @Override
    public List<ReservationStatusHistory> findByReservationId(Long reservationId) {
        return repository.findByReservationIdOrderByIdAsc(reservationId);
    }
}
