package com.iseonjwa.reservation.lottery.domain;

import com.iseonjwa.common.response.ErrorCode;

/**
 * 추첨 도메인 에러 코드. 공통 코드(VALIDATION_FAILED, NOT_FOUND 등)는 {@code CommonErrorCode}를 쓴다.
 */
// Note: 엔티티가 설정 시점 규칙을 지키며 이 코드를 던지므로 application이 아니라 domain에 둔다.
// domain이 application에 의존하면 계층 방향이 거꾸로 된다(user-service ISE-47과 같은 문제).
public enum LotteryErrorCode implements ErrorCode {
    LOTTERY_ALREADY_EXISTS(409, "이미 추첨을 설정한 회차입니다"),
    LOTTERY_SETUP_CLOSED(409, "일반 예매 오픈이나 응모 시작이 지나 추첨을 설정할 수 없습니다"),
    LOTTERY_SEATS_NOT_ENOUGH(409, "추첨석으로 뗄 수 있는 좌석이 모자랍니다"),
    LOTTERY_NOT_OPEN(409, "응모 기간이 아닙니다"),
    LOTTERY_ALREADY_ENTERED(409, "이미 응모한 추첨입니다. 취소한 뒤에도 다시 응모할 수 없습니다");

    private final int status;
    private final String message;

    LotteryErrorCode(int status, String message) {
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
