package com.iseonjwa.reservation.concert.application;

import com.iseonjwa.reservation.concert.domain.repository.SeatLayoutRow;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

// 회차 좌석 조회 결과. 캐시한 배치와, 1초 캐시에서 읽은 선점 가능 여부(배치와 같은 순서)를 함께 준다.
// 같은 1초 안의 요청은 같은 객체를 나눠 쓰므로 받은 쪽이 배열을 고치지 않는다(ISE-264)
@Getter
@AllArgsConstructor
public class ScheduleSeatsResult {

    // 구역 → 열 → 번호 순 배치
    private List<SeatLayoutRow> seats;

    // available[i]는 seats.get(i) 좌석을 일반 선점이 잡을 수 있는지다
    private boolean[] available;
}
