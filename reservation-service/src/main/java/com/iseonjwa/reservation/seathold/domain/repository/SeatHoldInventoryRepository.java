package com.iseonjwa.reservation.seathold.domain.repository;

import com.iseonjwa.reservation.concert.domain.entity.Schedule;
import com.iseonjwa.reservation.concert.domain.entity.ScheduleSeat;
import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 선점에 필요한 회차·회차 좌석 조회와 좌석 상태 전이. concert 모듈 엔티티를 읽기만 하고 저장소는 선점 모듈이 가진다(ADR 0034).
 * 좌석 상태 전이(AVAILABLE → HELD, 만료 시 HELD → AVAILABLE)는 선점 모듈 책임이다(ScheduleSeatStatus).
 */
public interface SeatHoldInventoryRepository {
    // 지운 회차(deleted_at)는 찾지 않는다(Schedule의 @SQLRestriction)
    Optional<Schedule> findSchedule(Long scheduleId);

    // 다른 회차의 좌석 ID는 결과에서 빠진다. 요청 개수와 비교해 회차 소속을 확인한다
    List<ScheduleSeat> findSeats(Long scheduleId, List<Long> seatIds);

    // findSeats와 같되 회차를 함께 읽는다(DB 왕복 1번). 지운 회차의 좌석은 빠진다. 결과가 비면 회차가 없거나
    // 좌석 ID가 모두 다른 회차의 것이므로, 호출한 쪽이 findSchedule로 둘을 가른다
    List<ScheduleSeat> findSeatsWithSchedule(Long scheduleId, List<Long> seatIds);

    // 선점 사전 판정에 필요한 것(좌석과 회차, 멱등키 행 존재, 입장 토큰 사용 여부)을 한 문장으로 읽는다(DB 왕복 1번).
    // 한 문장이라 같은 시점의 데이터다. 좌석이 하나도 없으면 결과가 비어 멱등키·토큰 값도 없다(호출한 쪽이 따로 읽는다)
    HoldPrecheck precheck(Long scheduleId, List<Long> seatIds, UUID requestId, String entryTokenJti);

    record HoldPrecheck(List<ScheduleSeat> seats, boolean requestExists, boolean tokenActive) {}

    // 이 트랜잭션의 남은 문장이 행 잠금을 timeout보다 오래 기다리지 않게 한다(SET LOCAL lock_timeout, SA 9-4)
    void applyLockTimeout(Duration timeout);

    // AVAILABLE인 좌석만 HELD로 바꾸고 바꾼 행 수를 돌려준다(SA 12-7). 요청 수보다 작으면 이미 잡힌 좌석이 섞인 것이다
    int holdAvailableSeats(Long scheduleId, List<Long> seatIds, Instant now, Long actorId);

    // 자동 배정용(ISE-264). 회차의 선점 가능한 좌석 중 grade·section(null이면 조건 없음)에 맞는 좌석을 ID 오름차순으로
    // 최대 limit개 잠그고 그 ID를 돌려준다. 다른 트랜잭션이 잠근 행은 기다리지 않고 건너뛴다(FOR UPDATE SKIP LOCKED).
    // 남은 좌석이 모자라면 limit보다 적게 돌려준다
    List<Long> lockAvailableSeatIds(Long scheduleId, SeatGrade grade, String section, int limit);

    // 추첨 당첨 예매용(D10). AVAILABLE인 LOTTERY 좌석만 HELD로 바꾸고 바꾼 행 수를 돌려준다. 일반 좌석은 잡지 않는다
    int holdAvailableLotterySeats(Long scheduleId, List<Long> seatIds, Instant now, Long actorId);

    // HELD인 좌석만 AVAILABLE로 되돌리고 바꾼 행 수를 돌려준다. 선점 만료가 쓴다
    int releaseHeldSeats(Long scheduleId, List<Long> seatIds, Instant now, Long actorId);
}
