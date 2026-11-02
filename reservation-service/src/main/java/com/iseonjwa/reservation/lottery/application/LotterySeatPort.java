package com.iseonjwa.reservation.lottery.application;

import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;

/**
 * 추첨이 회차 좌석을 바꾸는 유일한 통로(details.md 4절 모듈 경계). 인자는 ID와 값만 받는다.
 * 지금은 같은 DB·트랜잭션의 좌석 저장소를 부르고, 서비스를 나누면 구현만 내부 API 호출로 바꾼다.
 */
public interface LotterySeatPort {

    /**
     * {@code sourceGrade}의 AVAILABLE 좌석을 ID 오름차순으로 최대 {@code seatCount}개 골라 등급을 {@code LOTTERY}로 바꾼다(D9).
     *
     * @return 실제로 바꾼 좌석 수. {@code seatCount}보다 작으면 호출한 쪽이 트랜잭션을 되돌려야 한다
     */
    int convertToLottery(Long scheduleId, SeatGrade sourceGrade, int seatCount, Long actorId);
}
