package com.iseonjwa.reservation.seathold.domain.repository;

import com.iseonjwa.reservation.seathold.domain.entity.SeatHoldRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SeatHoldRequestRepository {
    // 같은 request_id가 이미 있으면 PK 제약 위반(DataIntegrityViolationException)이 난다.
    // 같은 입장 토큰의 거절되지 않은 요청이 이미 있으면 EntryTokenAlreadyUsedException이 난다.
    SeatHoldRequest save(SeatHoldRequest request);

    Optional<SeatHoldRequest> findById(UUID requestId);

    // 이 입장 토큰으로 거절되지 않은(REQUESTED·HELD) 선점 요청이 있는지 본다
    boolean existsActiveByEntryTokenJti(String entryTokenJti);

    // 멱등키 행이 있는지와 이 입장 토큰이 거절되지 않은 요청에 쓰였는지를 한 번에 읽는다(DB 왕복 1번).
    // entryTokenJti가 null이면 tokenActive는 false다
    RequestKeyState findKeyState(UUID requestId, String entryTokenJti);

    record RequestKeyState(boolean requestExists, boolean tokenActive) {}

    // REQUESTED 요청을 HELD로 바꾸고 예매를 연결한다. 바꾼 행 수를 돌려준다
    int markHeld(UUID requestId, Long reservationId, Instant now, Long actorId);

    // 자동 배정 요청(REQUESTED)을 HELD로 바꾸고 서버가 고른 좌석과 예매를 기록한다. 바꾼 행 수를 돌려준다(ISE-264)
    int markAutoHeld(UUID requestId, Long reservationId, List<Long> seatIds, Instant now, Long actorId);

    // REQUESTED 요청을 잠그고 접수 시각을 돌려준다. 이미 닫혔거나 없으면 비어 있다. 30초 시한 정리와 순서를 정하려고 잠근다(ISE-17)
    Optional<Instant> lockRequestedCreatedAt(UUID requestId);

    // REQUESTED 요청을 REJECTED(reasonCode)로 바꾼다. 바꾼 행 수를 돌려준다
    int markRejected(UUID requestId, String reasonCode, Instant now);

    // createdBefore 이전에 접수돼 아직 REQUESTED인 요청을 오래된 순으로 limit건까지 REJECTED(TIMEOUT)으로 닫는다.
    // 소비자가 처리 중이라 잠긴 행은 건너뛴다. 닫은 행 수를 돌려준다
    int rejectTimedOut(Instant createdBefore, Instant now, int limit);
}
