package com.iseonjwa.reservation.concert.domain.repository;

import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;
import com.iseonjwa.reservation.global.vo.Money;

import java.util.Map;

public interface ScheduleSeatRepository {
    boolean existsByScheduleId(Long scheduleId);

    // 공연장 좌석 배치를 회차 좌석으로 한 번에 복사하고, 만든 좌석 수를 돌려준다.
    // 가격은 좌석 등급으로 고르고 상태는 AVAILABLE이다.
    int createFromVenueSeats(Long scheduleId, Long venueId, Map<SeatGrade, Money> prices, Long actorId);

    // 추첨 설정(D9). sourceGrade의 AVAILABLE 좌석을 ID 오름차순으로 최대 seatCount개 LOTTERY로 바꾸고, 바꾼 수를 돌려준다
    int convertToLottery(Long scheduleId, SeatGrade sourceGrade, int seatCount, Long actorId);

    // 추첨 수정(D9). LOTTERY 좌석을 ID 내림차순으로 최대 seatCount개 sourceGrade로 되돌리고, 되돌린 수를 돌려준다
    int revertFromLottery(Long scheduleId, SeatGrade sourceGrade, int seatCount, Long actorId);
}
