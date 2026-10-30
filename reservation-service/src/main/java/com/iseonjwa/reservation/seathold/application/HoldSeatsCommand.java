package com.iseonjwa.reservation.seathold.application;

import java.util.List;
import java.util.UUID;

// requestId는 Idempotency-Key 값 그대로다. seatIds는 요청 순서 그대로 받고 서비스가 검증·정렬한다
public record HoldSeatsCommand(Long userId, UUID requestId, Long scheduleId, List<Long> seatIds) {}
