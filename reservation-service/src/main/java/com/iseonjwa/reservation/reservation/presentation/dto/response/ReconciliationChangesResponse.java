package com.iseonjwa.reservation.reservation.presentation.dto.response;

import com.iseonjwa.reservation.reservation.domain.entity.Reservation;
import lombok.Getter;

import java.util.List;

// 기간 안에 확정·취소된 예매 한 쪽. nextCursor가 null이면 마지막 쪽이다
@Getter
public class ReconciliationChangesResponse {
    private final List<ReconciliationReservationResponse> items;
    private final Long nextCursor;

    private ReconciliationChangesResponse(List<ReconciliationReservationResponse> items, Long nextCursor) {
        this.items = items;
        this.nextCursor = nextCursor;
    }

    // 한 쪽을 꽉 채웠으면 뒤에 더 있을 수 있어 마지막 ID를 다음 cursor로 준다
    public static ReconciliationChangesResponse of(List<Reservation> reservations, int pageSize) {
        var items = reservations.stream().map(ReconciliationReservationResponse::of).toList();
        var nextCursor = reservations.size() < pageSize ? null : reservations.getLast().getId();
        return new ReconciliationChangesResponse(items, nextCursor);
    }
}
