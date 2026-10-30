package com.iseonjwa.reservation.seathold.domain.repository;

import com.iseonjwa.reservation.concert.domain.entity.Schedule;
import com.iseonjwa.reservation.concert.domain.entity.ScheduleSeat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 선점에 필요한 회차·회차 좌석 조회와 좌석 상태 전이. concert 모듈 엔티티를 읽기만 하고 저장소는 선점 모듈이 가진다(ADR 0034).
 * 좌석 상태 전이(AVAILABLE → HELD)는 선점 모듈 책임이다(ScheduleSeatStatus).
 */
public interface SeatHoldInventoryRepository {
    // 지운 회차(deleted_at)는 찾지 않는다(Schedule의 @SQLRestriction)
    Optional<Schedule> findSchedule(Long scheduleId);

    // 다른 회차의 좌석 ID는 결과에서 빠진다. 요청 개수와 비교해 회차 소속을 확인한다
    List<ScheduleSeat> findSeats(Long scheduleId, List<Long> seatIds);

    // AVAILABLE인 좌석만 HELD로 바꾸고 바꾼 행 수를 돌려준다(SA 12-7). 요청 수보다 작으면 이미 잡힌 좌석이 섞인 것이다
    int holdAvailableSeats(Long scheduleId, List<Long> seatIds, Instant now, Long actorId);
}
