package com.iseonjwa.reservation.concert.presentation.dto.response;

import com.iseonjwa.reservation.concert.application.ConcertDetail.ScheduleDetail;
import com.iseonjwa.reservation.concert.domain.entity.SaleStatus;
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
    public static ScheduleSummaryResponse of(ScheduleDetail schedule, Instant serverTime) {
        return new ScheduleSummaryResponse(schedule.scheduleId(), schedule.venueName(),
                schedule.startsAt(), schedule.openAt(), schedule.maxPerUser(),
                schedule.saleStatusAt(serverTime));
    }
}
