package com.iseonjwa.reservation.lottery.domain.entity;

// OPEN(응모 중) → CLOSED(마감) → DRAWN(추첨 완료, 배정·승계 중) → DONE(종결, 남은 추첨석 반환)
public enum LotteryStatus {
    OPEN, CLOSED, DRAWN, DONE
}
