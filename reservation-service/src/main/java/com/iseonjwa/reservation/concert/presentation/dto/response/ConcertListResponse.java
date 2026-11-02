package com.iseonjwa.reservation.concert.presentation.dto.response;

import com.iseonjwa.reservation.concert.application.ConcertListResult;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

// SA 11-2 페이지 형식: {content, page, size, totalElements}
@Getter
@AllArgsConstructor
public class ConcertListResponse {

    private List<ConcertSummaryResponse> content;

    private int page;

    private int size;

    private long totalElements;

    public static ConcertListResponse from(ConcertListResult result) {
        List<ConcertSummaryResponse> content = result.getConcerts().stream()
                .map(concert -> ConcertSummaryResponse.of(concert, result.getNextSchedules().get(concert.getId())))
                .toList();
        return new ConcertListResponse(content, result.getPage(), result.getSize(), result.getTotalElements());
    }
}
