package com.iseonjwa.reservation.seathold.domain.repository;

import com.iseonjwa.reservation.seathold.domain.entity.SeatHoldRequest;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SeatHoldRequestRepository {
    // 같은 request_id가 이미 있으면 PK 제약 위반(DataIntegrityViolationException)이 난다.
    SeatHoldRequest save(SeatHoldRequest request);

    Optional<SeatHoldRequest> findById(UUID requestId);

    // 트랜잭션 없이(자동 커밋) 한 문장으로 있는지만 본다. 선점 사전 거절에 걸린 요청이 같은 멱등키 재요청인지 가르는 데 쓴다
    boolean existsById(UUID requestId);

    // REQUESTED 요청을 HELD로 바꾸고 예매를 연결한다. 바꾼 행 수를 돌려준다
    int markHeld(UUID requestId, Long reservationId, Instant now, Long actorId);
}
