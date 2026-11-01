package com.iseonjwa.reservation.concert.domain.repository;

import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;
import com.iseonjwa.reservation.global.vo.Money;

import java.util.Map;

public interface ScheduleSeatRepository {
    boolean existsByScheduleId(Long scheduleId);

    // 공연장 좌석 배치를 회차 좌석으로 한 번에 복사하고, 만든 좌석 수를 돌려준다.
    // 가격은 좌석 등급으로 고르고 상태는 AVAILABLE이다.
    int createFromVenueSeats(Long scheduleId, Long venueId, Map<SeatGrade, Money> prices, Long actorId);
}
