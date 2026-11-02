package com.iseonjwa.reservation.concert.presentation;

import com.iseonjwa.common.response.SuccessResponse;
import com.iseonjwa.reservation.concert.application.ConcertDetailResult;
import com.iseonjwa.reservation.concert.application.ConcertListResult;
import com.iseonjwa.reservation.concert.application.ConcertQueryService;
import com.iseonjwa.reservation.concert.presentation.dto.response.ConcertDetailResponse;
import com.iseonjwa.reservation.concert.presentation.dto.response.ConcertListResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 로그인 없이 호출한다(Gateway AccessPolicy의 PUBLIC_GET). 그래서 @RequireRole이 없다
@RestController
@RequestMapping("/api/v1/concerts")
@RequiredArgsConstructor
public class ConcertController {

    private final ConcertQueryService concertQueryService;

    // 공연 목록
    @GetMapping
    public ResponseEntity<SuccessResponse<ConcertListResponse>> getConcerts(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        ConcertListResult result = concertQueryService.getConcerts(page, size);
        return ResponseEntity.ok(SuccessResponse.of(200, "공연 목록을 조회했습니다",
                ConcertListResponse.from(result), MDC.get("traceId")));
    }

    // 공연 상세
    @GetMapping("/{concertId}")
    public ResponseEntity<SuccessResponse<ConcertDetailResponse>> getConcert(@PathVariable Long concertId) {
        ConcertDetailResult result = concertQueryService.getConcert(concertId);
        return ResponseEntity.ok(SuccessResponse.of(200, "공연을 조회했습니다",
                ConcertDetailResponse.from(result), MDC.get("traceId")));
    }
}
