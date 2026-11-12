package com.iseonjwa.reservation.seathold.infrastructure.persistence;

import com.iseonjwa.reservation.seathold.domain.entity.SeatHoldRequest;
import com.iseonjwa.reservation.seathold.domain.repository.SeatHoldRequestRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaSeatHoldRequestRepository implements SeatHoldRequestRepository {
    private final SpringDataSeatHoldRequestRepository repository;

    public JpaSeatHoldRequestRepository(SpringDataSeatHoldRequestRepository repository) {
        this.repository = repository;
    }

    @Override
    public SeatHoldRequest save(SeatHoldRequest request) {
        return repository.saveAndFlush(request);
    }

    @Override
    public int markHeld(UUID requestId, Long reservationId, Instant now, Long actorId) {
        return repository.markHeld(requestId, reservationId, now, actorId);
    }

    @Override
    public Optional<SeatHoldRequest> findById(UUID requestId) {
        return repository.findById(requestId);
    }
}
