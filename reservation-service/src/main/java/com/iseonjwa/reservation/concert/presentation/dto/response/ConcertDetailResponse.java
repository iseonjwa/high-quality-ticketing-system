package com.iseonjwa.reservation.concert.presentation.dto.response;

import com.iseonjwa.reservation.concert.application.ConcertDetailResult;
import com.iseonjwa.reservation.concert.domain.entity.Concert;
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
        Concert concert = result.getConcert();
        List<ScheduleSummaryResponse> schedules = result.getSchedules().stream()
                .map(schedule -> ScheduleSummaryResponse.of(schedule, result.getServerTime()))
                .toList();
        return new ConcertDetailResponse(concert.getId(), concert.getTitle(), concert.getDescription(),
                result.getServerTime(), schedules);
    }
}
