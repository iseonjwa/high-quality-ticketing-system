package com.iseonjwa.reservation.lottery.domain.entity;

// ENTERED(응모) → CANCELED(마감 전 취소) 또는 추첨 뒤 WON·WAITLISTED·LOST → PAID·FAILED
public enum LotteryEntryStatus {
    ENTERED, CANCELED, WON, WAITLISTED, LOST, PAID, FAILED
}
