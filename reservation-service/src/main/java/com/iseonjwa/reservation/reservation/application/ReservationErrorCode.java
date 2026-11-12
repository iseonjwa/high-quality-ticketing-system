package com.iseonjwa.reservation.reservation.application;

import com.iseonjwa.common.response.ErrorCode;

/**
 * 예매 취소 에러 코드(docs/design/reservation/contracts.md 6절). 공통 코드(VALIDATION_FAILED, NOT_FOUND)는
 * {@code CommonErrorCode}를 쓴다. 409는 정상 업무 거절로 집계한다(SA 3장).
 */
public enum ReservationErrorCode implements ErrorCode {
    RESERVATION_NOT_CANCELABLE(409, "취소할 수 없는 예매입니다"),
    LOTTERY_RESERVATION_NOT_CANCELABLE(409, "추첨으로 배정된 예매는 취소할 수 없습니다"),
    CANCEL_PERIOD_ENDED(409, "공연이 시작되어 취소할 수 없습니다");

    private final int status;
    private final String message;

    ReservationErrorCode(int status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public String message() {
        return message;
    }
}
