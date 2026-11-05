package com.iseonjwa.reservation.reservation.presentation.dto.response;

import com.iseonjwa.reservation.reservation.application.ReservationListResult;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

// SA 11-2 페이지 형식: {content, page, size, totalElements}
@Getter
@AllArgsConstructor
public class ReservationListResponse {

    private List<ReservationSummaryResponse> content;

    private int page;

    private int size;

    private long totalElements;

    public static ReservationListResponse from(ReservationListResult result) {
        List<ReservationSummaryResponse> content = result.getReservations().stream()
                .map(ReservationSummaryResponse::from)
                .toList();
        return new ReservationListResponse(content, result.getPage(), result.getSize(), result.getTotalElements());
    }
}
