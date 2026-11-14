package com.iseonjwa.reservation.seathold.domain.repository;

import com.iseonjwa.reservation.seathold.domain.entity.SeatHoldRequest;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SeatHoldRequestRepository {
    // 같은 request_id가 이미 있으면 PK 제약 위반(DataIntegrityViolationException)이 난다.
    // 같은 입장 토큰의 거절되지 않은 요청이 이미 있으면 EntryTokenAlreadyUsedException이 난다.
    SeatHoldRequest save(SeatHoldRequest request);

    Optional<SeatHoldRequest> findById(UUID requestId);

    // 이 입장 토큰으로 거절되지 않은(REQUESTED·HELD) 선점 요청이 있는지 본다
    boolean existsActiveByEntryTokenJti(String entryTokenJti);

    // REQUESTED 요청을 HELD로 바꾸고 예매를 연결한다. 바꾼 행 수를 돌려준다
    int markHeld(UUID requestId, Long reservationId, Instant now, Long actorId);

    // REQUESTED 요청을 잠그고 접수 시각을 돌려준다. 이미 닫혔거나 없으면 비어 있다. 30초 시한 정리와 순서를 정하려고 잠근다(ISE-17)
    Optional<Instant> lockRequestedCreatedAt(UUID requestId);

    // REQUESTED 요청을 REJECTED(reasonCode)로 바꾼다. 바꾼 행 수를 돌려준다
    int markRejected(UUID requestId, String reasonCode, Instant now);

    // createdBefore 이전에 접수돼 아직 REQUESTED인 요청을 오래된 순으로 limit건까지 REJECTED(TIMEOUT)으로 닫는다.
    // 소비자가 처리 중이라 잠긴 행은 건너뛴다. 닫은 행 수를 돌려준다
    int rejectTimedOut(Instant createdBefore, Instant now, int limit);
}
