package com.iseonjwa.reservation.queue.application;

import com.iseonjwa.common.response.ErrorCode;

public enum QueueErrorCode implements ErrorCode {
    SALE_NOT_OPEN(409, "판매 중인 회차가 아닙니다"),
    QUEUE_NOT_READY(409, "아직 입장 차례가 아닙니다"),
    QUEUE_NOT_JOINED(404, "대기열에 없습니다. 다시 진입해 주세요"),
    QUEUE_TEMPORARILY_UNAVAILABLE(503, "대기열이 잠시 혼잡합니다. 잠시 후 다시 시도해 주세요");

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
