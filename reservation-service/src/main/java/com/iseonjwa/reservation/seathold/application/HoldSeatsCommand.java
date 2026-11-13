package com.iseonjwa.reservation.seathold.application;

import java.util.List;
import java.util.UUID;

// requestId는 Idempotency-Key 값 그대로다. seatIds는 요청 순서 그대로 받고 서비스가 검증·정렬한다.
// entryTokenJti는 Gateway가 검증한 입장 토큰의 jti(X-Entry-Token-Id)다. 없으면 토큰을 소모하지 않는다(ADR 0053)
public record HoldSeatsCommand(Long userId, UUID requestId, Long scheduleId, List<Long> seatIds, String entryTokenJti) {

    // 입장 토큰 없이 서비스를 직접 부르는 경로(테스트 등)
    public HoldSeatsCommand(Long userId, UUID requestId, Long scheduleId, List<Long> seatIds) {
        this(userId, requestId, scheduleId, seatIds, null);
    }
}
