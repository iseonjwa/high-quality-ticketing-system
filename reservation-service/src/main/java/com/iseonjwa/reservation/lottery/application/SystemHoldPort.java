package com.iseonjwa.reservation.lottery.application;

import java.time.Instant;
import java.util.List;

/**
 * 추첨이 당첨 예매를 만드는 유일한 통로(D10, details.md 4절 모듈 경계). 인자는 ID와 값만 받는다.
 * 지금은 같은 트랜잭션의 선점 모듈을 부르고, 서비스를 나누면 구현만 내부 API 호출로 바꾼다.
 */
public interface SystemHoldPort {

    /**
     * 추첨석으로 HELD 예매({@code source = LOTTERY}, 선점 30분)를 만든다. 호출한 쪽의 트랜잭션 안에서 불러야 한다.
     *
     * @throws com.iseonjwa.common.response.BusinessException 매수 한도 초과나 이미 잡힌 좌석이면 예외
     */
    LotteryHold hold(Long userId, Long scheduleId, List<Long> seatIds);

    record LotteryHold(Long reservationId, long totalAmount, Instant holdExpiresAt) {}
}
