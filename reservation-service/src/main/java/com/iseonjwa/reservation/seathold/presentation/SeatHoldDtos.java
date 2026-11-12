package com.iseonjwa.reservation.seathold.presentation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.iseonjwa.reservation.seathold.application.SeatHoldRequestQueryService.SeatHoldRequestResult;
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
    // 접수형 폴링 간격(contracts.md 2-2절). 처리 시간을 재 본 뒤(ISE-18) 바꿀 수 있다
    private static final int NEXT_POLL_AFTER_SECONDS = 1;

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
    }

    // 접수형 202 본문. status는 늘 REQUESTED이고 "결과는 Location에서 확인하라"는 뜻이다(contracts.md 2-2절)
    public record AcceptedResponse(UUID requestId, String status, int nextPollAfterSeconds) {
        static AcceptedResponse of(UUID requestId) {
            return new AcceptedResponse(requestId, "REQUESTED", NEXT_POLL_AFTER_SECONDS);
        }
    }

    /**
     * 접수 결과 조회 본문(contracts.md 2-2절). 상태마다 필드가 다르다. REQUESTED는 nextPollAfterSeconds,
     * HELD는 reservation, REJECTED는 reasonCode만 더한다. reservation 블록 안의 null(결제 토큰)은 그대로 내린다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RequestResultResponse(UUID requestId, String status, Integer nextPollAfterSeconds,
            ReservationBlock reservation, String reasonCode) {
        static RequestResultResponse from(SeatHoldRequestResult result) {
            return switch (result.status()) {
                case REQUESTED -> new RequestResultResponse(result.requestId(), "REQUESTED",
                        NEXT_POLL_AFTER_SECONDS, null, null);
                case HELD -> new RequestResultResponse(result.requestId(), "HELD", null,
                        ReservationBlock.from(result.reservation()), null);
                case REJECTED -> new RequestResultResponse(result.requestId(), "REJECTED", null, null,
                        result.reasonCode());
            };
        }
    }

    // 동기형 201 응답에서 requestId만 뺀 9개 필드다(contracts.md 2-2절 예시)
    public record ReservationBlock(Long reservationId, String reservationNo, Long scheduleId, List<Long> seatIds,
            String status, OffsetDateTime holdExpiresAt, long totalAmount, String paymentToken,
            OffsetDateTime paymentTokenExpiresAt) {
        static ReservationBlock from(SeatHoldResult result) {
            return new ReservationBlock(result.reservationId(), result.reservationNo(), result.scheduleId(),
                    result.seatIds(), result.status().name(), kst(result.holdExpiresAt()),
                    result.totalAmount().toBigDecimal().longValueExact(), result.paymentToken(),
                    kst(result.paymentTokenExpiresAt()));
        }
    }

    private static OffsetDateTime kst(Instant instant) {
        return instant == null ? null : instant.atZone(KST).toOffsetDateTime();
    }
}
