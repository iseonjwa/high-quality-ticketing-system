package com.iseonjwa.reservation.seathold.application;

import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;

import java.util.UUID;

/**
 * 자동 배정 선점 요청(ISE-264). 좌석 대신 고를 범위와 매수를 받는다. grade와 section은 둘 중 하나만 오거나 둘 다 없다(회차 전체).
 * requestId·entryTokenJti는 {@link HoldSeatsCommand}와 같은 뜻이다.
 */
public record AutoHoldSeatsCommand(Long userId, UUID requestId, Long scheduleId, SeatGrade grade, String section,
        int quantity, String entryTokenJti) {

    public AutoHoldSeatsCommand {
        if (grade != null && section != null) {
            throw new IllegalArgumentException("grade와 section은 함께 줄 수 없습니다");
        }
        if (grade == SeatGrade.LOTTERY) {
            throw new IllegalArgumentException("추첨석은 자동 배정하지 않습니다");
        }
    }

    // 입장 토큰 없이 서비스를 직접 부르는 경로(테스트 등)
    public AutoHoldSeatsCommand(Long userId, UUID requestId, Long scheduleId, SeatGrade grade, String section,
            int quantity) {
        this(userId, requestId, scheduleId, grade, section, quantity, null);
    }

    // seat_hold_requests.auto_criteria에 남기고 같은 멱등키 재요청을 비교하는 값. 같은 조건이면 늘 같은 문자열이다
    String criteriaKey() {
        var scope = grade != null ? "grade=" + grade.name() : section != null ? "section=" + section : "any";
        return scope + ";quantity=" + quantity;
    }
}
