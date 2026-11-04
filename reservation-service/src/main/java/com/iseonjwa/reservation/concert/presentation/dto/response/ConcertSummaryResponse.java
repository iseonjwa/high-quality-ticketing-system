package com.iseonjwa.reservation.concert.presentation.dto.response;

import com.iseonjwa.reservation.concert.domain.entity.Concert;
import com.iseonjwa.reservation.concert.domain.entity.Schedule;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;

// 공연 목록의 한 줄
@Getter
@AllArgsConstructor
public class ConcertSummaryResponse {

    private Long concertId;

    private String title;

    // 아직 시작하지 않은 가장 빠른 회차의 공연 시작·예매 오픈 시각. 남은 회차가 없으면 null
    private Instant nextStartsAt;

    private Instant nextOpenAt;

    public static ConcertSummaryResponse of(Concert concert, Schedule nextSchedule) {
        if (nextSchedule == null) {
            return new ConcertSummaryResponse(concert.getId(), concert.getTitle(), null, null);
        }
        return new ConcertSummaryResponse(concert.getId(), concert.getTitle(),
                nextSchedule.getStartsAt(), nextSchedule.getOpenAt());
    }
}
