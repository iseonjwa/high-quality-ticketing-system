package com.iseonjwa.common.response;

/**
 * 실패 응답의 {@code code}·{@code status}·기본 {@code message}를 정한다.
 * 공통 코드는 {@link CommonErrorCode}, 업무 코드는 서비스마다 enum으로 구현한다 (SA 11-2, 11-3).
 */
public interface ErrorCode {

    /** HTTP 상태 코드. 실패 응답의 {@code status}와 같다. */
    int getStatus();

    /** 도메인 에러 코드. 예: {@code SEAT_ALREADY_HELD}. */
    String getCode();

    /** 사용자에게 보여 줄 기본 메시지. */
    String getMessage();
}
