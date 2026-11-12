package com.iseonjwa.reservation.seathold.presentation;

import java.util.Locale;

/**
 * 선점 API의 처리 방식(설정 {@code seat-hold.mode}). 기동할 때 한 번 읽고, 바꾸려면 재시작한다(ADR 0057).
 * 실행 중에 바꾸지 않아 요청 처리 도중 모드가 바뀌는 경계를 다루지 않는다.
 */
enum SeatHoldMode {
    // 좌석을 바로 잠그고 201로 결과를 준다(M2~M3 기준선)
    SYNC,
    // 접수만 하고 202를 준다. 결과는 조회 API로 본다(M4)
    ASYNC;

    // 오타로 의도와 다른 모드가 뜨지 않게, 모르는 값이면 기동을 실패시킨다
    static SeatHoldMode from(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "sync" -> SYNC;
            case "async" -> ASYNC;
            default -> throw new IllegalArgumentException("seat-hold.mode는 sync 또는 async여야 합니다: " + value);
        };
    }
}
