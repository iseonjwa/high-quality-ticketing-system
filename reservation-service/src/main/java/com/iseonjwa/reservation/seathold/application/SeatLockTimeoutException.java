package com.iseonjwa.reservation.seathold.application;

import com.iseonjwa.common.response.BusinessException;

/**
 * 좌석이나 매수 행 잠금을 시한 안에 얻지 못했다. 사용자에게는 {@code SEAT_ALREADY_HELD}와 같다(SA 12-7).
 * 접수형 소비자가 이 경우만 따로 세어 잠금 시한 값을 다시 볼 근거로 쓴다(ISE-17).
 */
public class SeatLockTimeoutException extends BusinessException {
    public SeatLockTimeoutException() {
        super(SeatHoldErrorCode.SEAT_ALREADY_HELD);
    }
}
