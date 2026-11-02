package com.iseonjwa.reservation.reservation.infrastructure.persistence;

import com.iseonjwa.reservation.reservation.domain.entity.Reservation;
import com.iseonjwa.reservation.reservation.domain.repository.ReservationRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
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

    @Override
    public List<Long> findExpiredHeldIds(Instant now, int limit) {
        return repository.findExpiredHeldIds(now, limit);
    }

    @Override
    public int expireHeld(Long reservationId, Instant now, Long actorId) {
        return repository.expireHeld(reservationId, now, actorId);
    }
}
