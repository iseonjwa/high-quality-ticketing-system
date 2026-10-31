package com.iseonjwa.reservation.queue.application;

import com.iseonjwa.common.response.ErrorCode;

public enum QueueErrorCode implements ErrorCode {
    SALE_NOT_OPEN(409, "아직 예매가 시작되지 않았습니다");

    private final int status;
    private final String message;

    QueueErrorCode(int status, String message) {
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
