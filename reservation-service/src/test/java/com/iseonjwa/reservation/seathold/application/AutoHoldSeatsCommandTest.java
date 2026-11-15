package com.iseonjwa.reservation.seathold.application;

import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// 자동 배정 요청의 멱등 비교 값(auto_criteria)과 입력 규칙(ISE-264)
class AutoHoldSeatsCommandTest {
    private static final UUID KEY = UUID.randomUUID();

    @Test
    void 조건_값은_범위와_매수가_같을_때만_같다() {
        assertEquals("section=A;quantity=2", command(null, "A", 2).criteriaKey());
        assertEquals("grade=VIP;quantity=1", command(SeatGrade.VIP, null, 1).criteriaKey());
        assertEquals("any;quantity=3", command(null, null, 3).criteriaKey());
        // 범위나 매수가 하나라도 다르면 다른 값이라 같은 멱등키 재요청으로 인정되지 않는다
        var keys = Set.of(command(null, "A", 2).criteriaKey(), command(null, "A", 1).criteriaKey(),
                command(null, "B", 2).criteriaKey(), command(SeatGrade.R, null, 2).criteriaKey(),
                command(null, null, 2).criteriaKey());
        assertEquals(5, keys.size());
    }

    @Test
    void 등급과_구역을_함께_주거나_추첨석을_고르면_만들지_않는다() {
        assertThrows(IllegalArgumentException.class, () -> command(SeatGrade.VIP, "A", 1));
        assertThrows(IllegalArgumentException.class, () -> command(SeatGrade.LOTTERY, null, 1));
    }

    private static AutoHoldSeatsCommand command(SeatGrade grade, String section, int quantity) {
        return new AutoHoldSeatsCommand(1L, KEY, 10L, grade, section, quantity);
    }
}
