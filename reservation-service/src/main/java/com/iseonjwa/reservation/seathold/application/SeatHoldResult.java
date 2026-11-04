package com.iseonjwa.reservation.seathold.application;

import com.iseonjwa.reservation.global.vo.Money;
import com.iseonjwa.reservation.reservation.domain.entity.ReservationStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// 동기형 선점 성공 결과(contracts.md 2-1절). seatIds는 오름차순이다
public record SeatHoldResult(UUID requestId, Long reservationId, String reservationNo, Long scheduleId,
        List<Long> seatIds, ReservationStatus status, Instant holdExpiresAt, Money totalAmount,
        String paymentToken, Instant paymentTokenExpiresAt) {}
