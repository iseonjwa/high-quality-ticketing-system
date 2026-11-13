package com.iseonjwa.reservation.concert.application;

import com.iseonjwa.reservation.concert.domain.repository.SeatLayoutRow;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

// 회차 좌석 조회 결과. 캐시한 배치와, 이번 요청에서 읽은 선점 가능 여부(배치와 같은 순서)를 함께 준다
@Getter
@AllArgsConstructor
public class ScheduleSeatsResult {

    // 구역 → 열 → 번호 순 배치
    private List<SeatLayoutRow> seats;

    // available[i]는 seats.get(i) 좌석을 일반 선점이 잡을 수 있는지다
    private boolean[] available;
}
