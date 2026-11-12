package com.iseonjwa.reservation.concert.presentation.dto.response;

import com.iseonjwa.reservation.concert.application.ScheduleSeatsResult;
import com.iseonjwa.reservation.concert.domain.repository.SeatLayoutRow;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 회차 좌석 조회 응답. 좌석을 구역별로 묶는다
@Getter
@AllArgsConstructor
public class ScheduleSeatsResponse {

    private Long scheduleId;

    // 서버 현재 시각. 화면이 시간 계산을 사용자 PC 시계가 아니라 이 값으로 한다
    private Instant serverTime;

    private List<SeatSectionResponse> sections;

    // 배치는 구역 → 열 → 번호 순으로 와야 한다. LinkedHashMap이 구역이 처음 나온 순서를 지킨다
    public static ScheduleSeatsResponse of(Long scheduleId, Instant serverTime, ScheduleSeatsResult result) {
        List<SeatLayoutRow> seats = result.getLayout().getSeats();
        boolean[] available = result.getAvailable();
        Map<String, SeatSectionResponse> sections = new LinkedHashMap<>();
        for (int i = 0; i < seats.size(); i++) {
            SeatLayoutRow seat = seats.get(i);
            sections.computeIfAbsent(seat.section(), section -> SeatSectionResponse.from(seat))
                    .add(seat, available[i]);
        }
        return new ScheduleSeatsResponse(scheduleId, serverTime, new ArrayList<>(sections.values()));
    }
}
