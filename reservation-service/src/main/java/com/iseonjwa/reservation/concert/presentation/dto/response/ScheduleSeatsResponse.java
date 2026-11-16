package com.iseonjwa.reservation.concert.presentation.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import tools.jackson.databind.util.RawValue;

import java.time.Instant;

// 회차 좌석 조회 응답. 좌석을 구역별로 묶는다
@Getter
@AllArgsConstructor
public class ScheduleSeatsResponse {

    private Long scheduleId;

    // 서버 현재 시각. 화면이 시간 계산을 사용자 PC 시계가 아니라 이 값으로 한다
    private Instant serverTime;

    // 구역 목록(SeatSectionResponse 배열)을 미리 직렬화한 JSON이다. 응답 모양은 객체 목록을 직렬화할 때와 같다(ISE-264)
    private RawValue sections;

    public static ScheduleSeatsResponse of(Long scheduleId, Instant serverTime, RawValue sections) {
        return new ScheduleSeatsResponse(scheduleId, serverTime, sections);
    }
}
