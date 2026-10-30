package com.iseonjwa.reservation.seathold.domain.repository;

import com.iseonjwa.reservation.seathold.domain.entity.SeatHoldRequest;

import java.util.Optional;
import java.util.UUID;

public interface SeatHoldRequestRepository {
    // 같은 request_id가 이미 있으면 PK 제약 위반(DataIntegrityViolationException)이 난다.
    SeatHoldRequest save(SeatHoldRequest request);

    Optional<SeatHoldRequest> findById(UUID requestId);
}
