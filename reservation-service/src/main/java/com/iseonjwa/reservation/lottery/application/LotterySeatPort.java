package com.iseonjwa.reservation.lottery.application;

import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;
import com.iseonjwa.reservation.lottery.domain.LotterySeat;

import java.util.List;

/**
 * 추첨이 회차 좌석을 바꾸는 유일한 통로로, 서비스를 나누면 구현만 내부 API 호출로 바꾼다(details.md 4절 모듈 경계).
 */
public interface LotterySeatPort {

    /**
     * {@code sourceGrade}의 AVAILABLE 좌석을 ID 오름차순으로 최대 {@code seatCount}개 골라 등급을 {@code LOTTERY}로 바꾼다(D9).
     *
     * @return 실제로 바꾼 좌석 수. {@code seatCount}보다 작으면 호출한 쪽이 트랜잭션을 되돌려야 한다
     */
    int convertToLottery(Long scheduleId, SeatGrade sourceGrade, int seatCount, Long actorId);

    /**
     * {@code LOTTERY} 좌석을 ID 내림차순으로 최대 {@code seatCount}개 골라 {@code sourceGrade}로 되돌린다(D9 수정).
     *
     * @return 실제로 되돌린 좌석 수
     */
    int revertFromLottery(Long scheduleId, SeatGrade sourceGrade, int seatCount, Long actorId);

    /**
     * 종결 때 회차의 추첨석을 상태와 상관없이 모두 {@code sourceGrade}로 되돌리고 되돌린 수를 돌려준다(D9).
     */
    int revertAllFromLottery(Long scheduleId, SeatGrade sourceGrade, Long actorId);

    /**
     * 지표로 쓰는 모든 회차의 남은 추첨석 수를 센다.
     */
    long countAllLotterySeats();

    /**
     * 추첨 배정 계산({@code SeatAllocator})에 넘길 회차의 추첨석 전체를 ID 오름차순으로 읽는다.
     */
    List<LotterySeat> findLotterySeats(Long scheduleId);

    /**
     * 배정 워커가 당첨자 좌석을 고르도록 아직 잡히지 않은(AVAILABLE) 추첨석만 읽는다.
     */
    List<LotterySeat> findAvailableLotterySeats(Long scheduleId);
}
