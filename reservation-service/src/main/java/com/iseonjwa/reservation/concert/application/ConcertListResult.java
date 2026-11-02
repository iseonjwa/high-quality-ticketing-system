package com.iseonjwa.reservation.concert.application;

import com.iseonjwa.reservation.concert.domain.entity.Concert;
import com.iseonjwa.reservation.concert.domain.entity.Schedule;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;
import java.util.Map;

// 공연 목록 한 페이지와, 공연별 다음 회차(아직 시작하지 않은 가장 빠른 회차)
@Getter
@AllArgsConstructor
public class ConcertListResult {

    private List<Concert> concerts;

    // key는 공연 ID. 남은 회차가 없는 공연은 들어 있지 않다
    private Map<Long, Schedule> nextSchedules;

    private int page;

    private int size;

    private long totalElements;
}
