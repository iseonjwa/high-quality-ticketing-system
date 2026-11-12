package com.iseonjwa.reservation.concert.domain.repository;

import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;
import com.iseonjwa.reservation.global.vo.Money;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface ScheduleSeatRepository {
    boolean existsByScheduleId(Long scheduleId);

    // 회차 좌석 배치(바뀌지 않는 값). 공연장 좌석(열·번호·원래 등급)을 같이 읽고, 구역 → 열 → 번호 순이다
    List<SeatLayoutRow> findSeatLayouts(Long scheduleId);

    // 회차 좌석의 등급과 상태(바뀌는 값). 순서는 보장하지 않는다
    List<SeatStateRow> findSeatStates(Long scheduleId);

    // 공연장 좌석 배치를 회차 좌석으로 한 번에 복사하고, 만든 좌석 수를 돌려준다.
    // 가격은 좌석 등급으로 고르고 상태는 AVAILABLE이다.
    int createFromVenueSeats(Long scheduleId, Long venueId, Map<SeatGrade, Money> prices, Long actorId);

    // 추첨 설정(D9). sourceGrade의 AVAILABLE 좌석을 ID 오름차순으로 최대 seatCount개 LOTTERY로 바꾸고, 바꾼 수를 돌려준다
    int convertToLottery(Long scheduleId, SeatGrade sourceGrade, int seatCount, Long actorId);

    // 추첨 수정(D9). LOTTERY 좌석을 ID 내림차순으로 최대 seatCount개 sourceGrade로 되돌리고, 되돌린 수를 돌려준다
    int revertFromLottery(Long scheduleId, SeatGrade sourceGrade, int seatCount, Long actorId);

    // 추첨 종결(D9). 회차의 LOTTERY 좌석을 상태와 상관없이 모두 sourceGrade로 되돌리고, 되돌린 수를 돌려준다
    int revertAllFromLottery(Long scheduleId, SeatGrade sourceGrade, Long actorId);

    // 남은 추첨석 지표. 진행 중인 추첨이 없는데 0이 아니면 반환이 빠진 것이다
    long countAllLotterySeats();

    // 추첨 배정 계산(D9). 회차의 LOTTERY 좌석을 ID 오름차순으로, 옆자리 판정에 필요한 구역·열·번호와 함께 돌려준다
    List<LotterySeatRow> findLotterySeats(Long scheduleId);

    // 추첨 배정(D10). 회차의 LOTTERY 좌석 중 AVAILABLE인 것만. 배정 워커가 당첨자 좌석을 고를 때 쓴다
    List<LotterySeatRow> findAvailableLotterySeats(Long scheduleId);

    // 결제 승인(SA 8-3). HELD인 좌석만 SOLD로 바꾸고 바꾼 수를 돌려준다
    int sellHeldSeats(Long scheduleId, List<Long> seatIds, Instant now);

    // 결제 거절·승인 뒤 만료(SA 8-3). HELD인 좌석만 AVAILABLE로 되돌리고 되돌린 수를 돌려준다
    int releaseHeldSeats(Long scheduleId, List<Long> seatIds, Instant now);

    // 예매 취소. SOLD인 좌석만 AVAILABLE로 되돌리고 되돌린 수를 돌려준다. actorId는 취소한 사용자다
    int releaseSoldSeats(Long scheduleId, List<Long> seatIds, Instant now, Long actorId);

    record LotterySeatRow(long seatId, String section, int rowNo, int seatNo) {}
}
