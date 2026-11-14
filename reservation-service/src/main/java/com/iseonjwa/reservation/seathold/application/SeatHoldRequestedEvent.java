package com.iseonjwa.reservation.seathold.application;

import java.util.List;
import java.util.UUID;

/**
 * 소비자가 읽은 {@code SeatHoldRequested}(SA 10-3). {@code eventId}는 봉투의 이벤트 ID로 중복 소비 검사에 쓴다.
 * 페이로드의 {@code section}은 메시지 키를 만든 근거일 뿐이라 읽지 않는다(ADR 0056).
 *
 * @param entryTokenJti 선점에 쓴 입장 토큰의 jti. 입장 토큰 없이 접수됐으면 null이다(ADR 0053)
 */
public record SeatHoldRequestedEvent(UUID eventId, UUID requestId, long userId, long scheduleId, List<Long> seatIds,
        String entryTokenJti) {
    public SeatHoldRequestedEvent {
        seatIds = List.copyOf(seatIds);
    }
}
