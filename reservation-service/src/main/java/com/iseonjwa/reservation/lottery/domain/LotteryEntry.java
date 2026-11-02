package com.iseonjwa.reservation.lottery.domain;

import com.iseonjwa.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * 추첨 응모 한 건. 응모·취소는 기간 판정을 DB 시각 하나로 하려고 저장소의 조건부 SQL로만 만들고 바꾼다(details.md 2절).
 * 이 엔티티는 조회용이다.
 */
@Entity
@Table(name = "lottery_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LotteryEntry extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long lotteryId;

    @Column(nullable = false, updatable = false)
    private UUID requestId;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, updatable = false)
    private short quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private LotteryEntryStatus status;

    private Integer drawRank;

    private Long reservationId;

    @Column(nullable = false)
    private short paymentAttempts;

    @Column(length = 30)
    private String failReason;

    // 같은 멱등키의 재요청이 처음 요청과 같은 내용인지 본다
    public boolean isSameRequest(Long userId, Long lotteryId, int quantity) {
        return this.userId.equals(userId) && this.lotteryId.equals(lotteryId) && this.quantity == quantity;
    }
}
