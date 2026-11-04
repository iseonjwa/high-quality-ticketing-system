package com.iseonjwa.reservation.concert.application;

import com.iseonjwa.common.response.ErrorCode;

/**
 * 공연·회차·좌석 도메인 에러 코드. 공통 코드(VALIDATION_FAILED, NOT_FOUND 등)는 {@code CommonErrorCode}를 쓴다.
 */
public enum ConcertErrorCode implements ErrorCode {
    SCHEDULE_SEATS_ALREADY_CREATED(409, "이미 좌석을 만든 회차입니다");

    private final int status;
    private final String message;

    ConcertErrorCode(int status, String message) {
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
