package com.iseonjwa.reservation.concert.domain.entity;

import java.time.Instant;

// 회차의 판매 상태(SA 11-5). 매진은 MVP 범위 밖이라 두지 않는다
public enum SaleStatus {
    BEFORE_OPEN, // 예매 오픈 전
    ON_SALE,     // 예매 오픈 ~ 공연 시작 전
    CLOSED;      // 공연 시작 이후

    // 주어진 시각 기준 판매 상태. 오픈 시각 정각부터 판매 중이고, 공연 시작 정각부터 마감이다.
    // 회차 엔티티와 캐시에 두는 회차 값(ConcertDetail)이 같은 경계를 쓰도록 여기 한 곳에 둔다
    public static SaleStatus at(Instant openAt, Instant startsAt, Instant now) {
        if (now.isBefore(openAt)) {
            return BEFORE_OPEN;
        }
        if (now.isBefore(startsAt)) {
            return ON_SALE;
        }
        return CLOSED;
    }
}
