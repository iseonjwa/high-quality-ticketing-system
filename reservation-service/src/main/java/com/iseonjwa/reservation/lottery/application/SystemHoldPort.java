package com.iseonjwa.reservation.lottery.application;

import java.time.Instant;
import java.util.List;

/**
 * 추첨이 당첨 예매를 만드는 유일한 통로로, 서비스를 나누면 구현만 내부 API 호출로 바꾼다(D10, details.md 4절 모듈 경계).
 */
public interface SystemHoldPort {

    /**
     * 호출한 쪽의 트랜잭션 안에서 추첨석으로 HELD 예매({@code source = LOTTERY}, 선점 30분)를 만든다.
     *
     * @throws com.iseonjwa.common.response.BusinessException 매수 한도 초과나 이미 잡힌 좌석이면 예외
     */
    LotteryHold hold(Long userId, Long scheduleId, List<Long> seatIds);

    record LotteryHold(Long reservationId, long totalAmount, Instant holdExpiresAt) {}
}
