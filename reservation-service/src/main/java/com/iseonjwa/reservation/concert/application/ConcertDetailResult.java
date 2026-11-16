package com.iseonjwa.reservation.concert.application;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;

// 공연 상세. serverTime은 판매 상태를 판정한 기준 시각이고 응답에도 그대로 내보낸다.
// detail은 최대 1초 묵은 캐시 값이고 serverTime은 요청마다 새로 읽는다
@Getter
@AllArgsConstructor
public class ConcertDetailResult {

    private ConcertDetail detail;

    private Instant serverTime;
}
