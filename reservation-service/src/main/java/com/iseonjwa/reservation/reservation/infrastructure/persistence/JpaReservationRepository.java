package com.iseonjwa.reservation.reservation.infrastructure.persistence;

import com.iseonjwa.reservation.reservation.domain.entity.Reservation;
import com.iseonjwa.reservation.reservation.domain.repository.ReservationRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaReservationRepository implements ReservationRepository {
    private final SpringDataReservationRepository repository;

    public JpaReservationRepository(SpringDataReservationRepository repository) {
        this.repository = repository;
    }

    @Override
    public Reservation save(Reservation reservation) {
        return repository.saveAndFlush(reservation);
    }

    @Override
    public Optional<Reservation> findById(Long id) {
        return repository.findById(id);
    }

    @Override
    public Optional<Reservation> findByRequestId(UUID requestId) {
        return repository.findByRequestId(requestId);
    }
}
