package com.iseonjwa.reservation.concert.presentation.dto.response;

import com.iseonjwa.reservation.concert.application.ConcertDetail;
import com.iseonjwa.reservation.concert.application.ConcertDetailResult;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

@Getter
@AllArgsConstructor
public class ConcertDetailResponse {

    private Long concertId;

    private String title;

    private String description;

    // 서버 현재 시각. 화면이 "오픈까지 남은 시간"을 사용자 PC 시계가 아니라 이 값으로 계산한다
    private Instant serverTime;

    private List<ScheduleSummaryResponse> schedules;

    public static ConcertDetailResponse from(ConcertDetailResult result) {
        ConcertDetail detail = result.getDetail();
        List<ScheduleSummaryResponse> schedules = detail.schedules().stream()
                .map(schedule -> ScheduleSummaryResponse.of(schedule, result.getServerTime()))
                .toList();
        return new ConcertDetailResponse(detail.concertId(), detail.title(), detail.description(),
                result.getServerTime(), schedules);
    }
}
