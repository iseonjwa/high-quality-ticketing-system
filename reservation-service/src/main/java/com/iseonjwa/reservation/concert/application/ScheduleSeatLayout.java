package com.iseonjwa.reservation.concert.application;

import com.iseonjwa.reservation.concert.domain.repository.SeatLayoutRow;
import lombok.Getter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 회차 하나의 좌석 배치(구역 → 열 → 번호 순). 만든 뒤에는 바꾸지 않아 여러 요청이 같이 읽어도 된다
public class ScheduleSeatLayout {

    @Getter
    private final List<SeatLayoutRow> seats;

    // 좌석 ID → seats 안의 위치. 요청마다 읽는 상태를 배치 순서에 맞춰 놓을 때 쓴다
    private final Map<Long, Integer> positionBySeatId;

    public ScheduleSeatLayout(List<SeatLayoutRow> seats) {
        this.seats = List.copyOf(seats);
        this.positionBySeatId = new HashMap<>(seats.size() * 2);
        for (int i = 0; i < seats.size(); i++) {
            positionBySeatId.put(seats.get(i).seatId(), i);
        }
    }

    public boolean isEmpty() {
        return seats.isEmpty();
    }

    public int size() {
        return seats.size();
    }

    // 배치에 없는 좌석이면 -1이다
    public int positionOf(Long seatId) {
        return positionBySeatId.getOrDefault(seatId, -1);
    }
}
