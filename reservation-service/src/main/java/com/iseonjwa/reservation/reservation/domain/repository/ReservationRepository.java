package com.iseonjwa.reservation.reservation.domain.repository;

import com.iseonjwa.reservation.reservation.domain.entity.Reservation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository {
    Reservation save(Reservation reservation);

    Optional<Reservation> findById(Long id);

    Optional<Reservation> findByRequestId(UUID requestId);

    // 선점 기한(hold_expires_at)이 now 이전인 HELD 예매 ID를 기한이 이른 순으로 limit건까지 돌려준다
    List<Long> findExpiredHeldIds(Instant now, int limit);

    // HELD이고 기한이 지난 예매만 EXPIRED로 바꾸고 바꾼 행 수를 돌려준다. 0이면 다른 쪽이 먼저 상태를 바꾼 것이다
    int expireHeld(Long reservationId, Instant now, Long actorId);
}
