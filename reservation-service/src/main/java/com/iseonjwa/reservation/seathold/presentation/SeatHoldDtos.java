package com.iseonjwa.reservation.seathold.presentation;

import com.iseonjwa.reservation.seathold.application.SeatHoldResult;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

public final class SeatHoldDtos {
    // 응답 시각은 한국 시간 오프셋(+09:00)으로 내린다(contracts.md 2-1절)
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private SeatHoldDtos() {}

    // 빈 배열·중복·매수 초과는 형식 오류가 아니라 SEAT_COUNT_MISMATCH라서 서비스가 검증한다
    public record HoldRequest(@NotNull List<@NotNull @Positive Long> seatIds) {}

    public record HoldResponse(UUID requestId, Long reservationId, String reservationNo, Long scheduleId,
            List<Long> seatIds, String status, OffsetDateTime holdExpiresAt, long totalAmount, String paymentToken,
            OffsetDateTime paymentTokenExpiresAt) {
        static HoldResponse from(SeatHoldResult result) {
            return new HoldResponse(result.requestId(), result.reservationId(), result.reservationNo(),
                    result.scheduleId(), result.seatIds(), result.status().name(), kst(result.holdExpiresAt()),
                    result.totalAmount().toBigDecimal().longValueExact(), result.paymentToken(),
                    kst(result.paymentTokenExpiresAt()));
        }

        private static OffsetDateTime kst(Instant instant) {
            return instant == null ? null : instant.atZone(KST).toOffsetDateTime();
        }
    }
}
