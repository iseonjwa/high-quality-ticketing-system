package com.iseonjwa.reservation.concert.presentation.dto.response;

import com.iseonjwa.reservation.concert.domain.entity.SaleStatus;
import com.iseonjwa.reservation.concert.domain.entity.Schedule;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;

// 공연 상세 안의 회차 한 줄
@Getter
@AllArgsConstructor
public class ScheduleSummaryResponse {

    private Long scheduleId;

    private String venueName;

    private Instant startsAt;

    private Instant openAt;

    private int maxPerUser;

    private SaleStatus saleStatus;

    // 판매 상태는 응답의 serverTime과 같은 시각으로 판정해야 화면과 어긋나지 않는다
    public static ScheduleSummaryResponse of(Schedule schedule, Instant serverTime) {
        return new ScheduleSummaryResponse(schedule.getId(), schedule.getVenue().getName(),
                schedule.getStartsAt(), schedule.getOpenAt(), schedule.getMaxPerUser(),
                schedule.saleStatusAt(serverTime));
    }
}
