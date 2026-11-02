package com.iseonjwa.reservation.lottery.presentation.dto;

import com.iseonjwa.reservation.lottery.domain.Lottery;
import com.iseonjwa.reservation.lottery.domain.LotteryEntry;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public final class LotteryEntryDtos {

    private LotteryEntryDtos() {}

    public record EntryRequest(@NotNull @Min(1) @Max(2) Integer quantity) {}

    // drawRank는 추첨 순위다. 예비자에게는 승계 순번이 된다. 추첨 전에는 비어 있다
    public record EntryResponse(Long entryId, Long lotteryId, String status, int quantity, Integer drawRank,
                                Long reservationId, String failReason) {
        public static EntryResponse from(LotteryEntry entry) {
            return new EntryResponse(entry.getId(), entry.getLotteryId(), entry.getStatus().name(),
                    entry.getQuantity(), entry.getDrawRank(), entry.getReservationId(), entry.getFailReason());
        }
    }

    /**
     * 공개 추첨 정보와 응모 규칙(D4·D5·D7). 시드는 공개하지 않는다(시드 약속은 확장안 ISE-95).
     */
    public record LotteryInfoResponse(Long lotteryId, Long scheduleId, String status, int seatCount,
                                      Instant entryStartAt, Instant entryEndAt, Instant drawAt, Rules rules) {
        public static LotteryInfoResponse from(Lottery lottery) {
            return new LotteryInfoResponse(lottery.getId(), lottery.getScheduleId(), lottery.getStatus().name(),
                    lottery.getSeatCount(), lottery.getEntryStartAt(), lottery.getEntryEndAt(), lottery.getDrawAt(),
                    new Rules(1, lottery.getMaxPerEntry(), false, false, true));
        }
    }

    /**
     * @param adjacentSeatsGuaranteed 2매 당첨이어도 옆자리를 보장하지 않는다(옆자리 우선 배정)
     * @param reentryAllowed          취소한 뒤 같은 추첨에 다시 응모할 수 없다(D5)
     * @param billingKeyRequired      당첨 때 자동결제하므로 빌링키 등록이 필요하다. 응모 때는 확인하지 않는다(D4)
     */
    public record Rules(int minPerEntry, int maxPerEntry, boolean adjacentSeatsGuaranteed, boolean reentryAllowed,
                        boolean billingKeyRequired) {}
}
