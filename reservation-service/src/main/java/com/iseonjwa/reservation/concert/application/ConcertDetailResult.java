package com.iseonjwa.reservation.concert.application;

import com.iseonjwa.reservation.concert.domain.entity.Concert;
import com.iseonjwa.reservation.concert.domain.entity.Schedule;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

// 공연 상세. serverTime은 판매 상태를 판정한 기준 시각이고 응답에도 그대로 내보낸다
@Getter
@AllArgsConstructor
public class ConcertDetailResult {

    private Concert concert;

    // 공연장까지 읽어 둔 회차들(시작 시각 오름차순)
    private List<Schedule> schedules;

    private Instant serverTime;
}
