package com.iseonjwa.reservation.concert.presentation.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Getter
@NoArgsConstructor
public class ScheduleCreateRequest {

    @NotNull
    @Positive
    private Long venueId;

    // ISO-8601 형식이다. 예: "2026-12-20T10:00:00Z"
    @NotNull
    private Instant startsAt;

    @NotNull
    private Instant openAt;

    // 비우면 기본값(4, 5)을 쓴다. 상한은 DB 컬럼 SMALLINT에 맞춘다
    @Min(1)
    @Max(Short.MAX_VALUE)
    private Integer maxPerUser;

    @Min(1)
    @Max(Short.MAX_VALUE)
    private Integer holdMinutes;
}
