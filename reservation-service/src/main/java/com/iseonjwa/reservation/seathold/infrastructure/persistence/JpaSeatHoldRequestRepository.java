package com.iseonjwa.reservation.seathold.infrastructure.persistence;

import com.iseonjwa.reservation.seathold.domain.entity.SeatHoldRequest;
import com.iseonjwa.reservation.seathold.domain.repository.SeatHoldRequestRepository;
import org.springframework.stereotype.Repository;

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
    public Optional<SeatHoldRequest> findById(UUID requestId) {
        return repository.findById(requestId);
    }
}
