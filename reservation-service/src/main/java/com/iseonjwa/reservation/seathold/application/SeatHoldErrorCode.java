package com.iseonjwa.reservation.seathold.application;

import com.iseonjwa.common.response.ErrorCode;

/**
 * 좌석 선점 에러 코드(docs/design/reservation/contracts.md 4절). 공통 코드(VALIDATION_FAILED, NOT_FOUND,
 * IDEMPOTENCY_KEY_REUSED)는 {@code CommonErrorCode}를 쓴다. 409는 정상 업무 거절로 집계한다(SA 3장).
 */
public enum SeatHoldErrorCode implements ErrorCode {
    SEAT_COUNT_MISMATCH(400, "선택한 좌석 수나 좌석이 올바르지 않습니다"),
    SALE_NOT_OPEN(409, "판매 중인 회차가 아닙니다"),
    PURCHASE_LIMIT_EXCEEDED(409, "회차당 구매 가능 매수를 넘었습니다"),
    SEAT_ALREADY_HELD(409, "이미 선택된 좌석입니다"),
    // 같은 멱등키 재요청인데 첫 선점이 이미 닫혔거나 결제 토큰을 줄 수 있는 시각이 지났다(contracts.md 3-2 ④)
    RESERVATION_NOT_PAYABLE(409, "결제할 수 없는 예매입니다"),
    // 이 입장 토큰으로 이미 선점했다. 403이라 프론트가 ENTRY_TOKEN_INVALID처럼 토큰을 지우고 대기열로 보낸다(ADR 0053 결정 4)
    ENTRY_TOKEN_USED(403, "이미 좌석 선점에 쓴 입장 토큰입니다. 대기열에 다시 서 주세요");

    private final int status;
    private final String message;

    SeatHoldErrorCode(int status, String message) {
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
