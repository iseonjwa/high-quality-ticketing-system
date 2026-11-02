package com.iseonjwa.reservation.concert.domain.entity;

// 회차의 판매 상태(SA 11-5). 매진은 MVP 범위 밖이라 두지 않는다
public enum SaleStatus {
    BEFORE_OPEN, // 예매 오픈 전
    ON_SALE,     // 예매 오픈 ~ 공연 시작 전
    CLOSED       // 공연 시작 이후
}
