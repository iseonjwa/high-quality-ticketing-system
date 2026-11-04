package com.iseonjwa.reservation.reservation.domain.entity;

import com.iseonjwa.reservation.global.vo.Money;
import com.iseonjwa.reservation.seathold.domain.entity.SeatHoldRequest;
import com.iseonjwa.reservation.seathold.domain.entity.SeatHoldRequestStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReservationTest {
    private static final Instant HOLD_EXPIRES_AT = Instant.parse("2026-11-01T11:05:00Z");

    @Test
    void 예매_선점_시_좌석_가격_합으로_총액과_수량을_계산하고_HELD로_시작한다() {
        var requestId = UUID.randomUUID();
        var reservation = Reservation.hold(
                "R-20261101-0001",
                requestId,
                10L,
                20L,
                List.of(Money.of(165_000), Money.of(143_000)),
                HOLD_EXPIRES_AT);

        assertEquals(2, reservation.getQuantity());
        assertEquals(Money.of(308_000), reservation.getTotalAmount());
        assertEquals(ReservationStatus.HELD, reservation.getStatus());
        assertEquals(ReservationSource.GENERAL, reservation.getSource());
        assertEquals(requestId, reservation.getRequestId());
    }

    @Test
    void 선점할_좌석_가격_목록이_비어_있으면_예매를_만들_수_없다() {
        assertThrows(IllegalArgumentException.class,
                () -> Reservation.hold("R-20261101-0001", UUID.randomUUID(), 10L, 20L, List.of(), HOLD_EXPIRES_AT));
    }

    @Test
    void 선점_접수_요청은_좌석_목록을_방어적_복사하고_새_엔티티로_표시한다() {
        var mutableSeats = new ArrayList<>(List.of(101L, 102L));
        var request = SeatHoldRequest.request(UUID.randomUUID(), 10L, 20L, mutableSeats);
        mutableSeats.add(103L);

        assertEquals(List.of(101L, 102L), request.getSeatIds());
        assertEquals(SeatHoldRequestStatus.REQUESTED, request.getStatus());
        assertTrue(request.isNew());
    }

    @Test
    void 선점할_좌석이_비어_있으면_접수_요청을_만들_수_없다() {
        assertThrows(IllegalArgumentException.class,
                () -> SeatHoldRequest.request(UUID.randomUUID(), 10L, 20L, List.of()));
    }
}
