package com.iseonjwa.reservation.reservation.domain.entity;

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

import java.time.Instant;

/**
 * 예매 상태 전이 한 건의 기록. 기록용 테이블이라 BaseEntity를 상속하지 않고 한 번 쓰면 바꾸지 않는다(SA 12-1).
 */
@Getter
@Entity
@Table(name = "reservation_status_histories")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationStatusHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_id", nullable = false, updatable = false)
    private Long reservationId;

    // 최초 생성(HELD) 기록은 이전 상태가 없어 null이다
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false, length = 20)
    private ReservationStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false, length = 20)
    private ReservationStatus toStatus;

    // 예: PAYMENT_DECLINED, HOLD_EXPIRED. 소비자·스케줄러가 바꾸면 사람 컬럼이 없으므로 이 값이 변경 이유다
    @Column(updatable = false, length = 50)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private ReservationStatusHistory(Long reservationId, ReservationStatus fromStatus, ReservationStatus toStatus,
            String reason, Instant createdAt) {
        this.reservationId = reservationId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public static ReservationStatusHistory of(Long reservationId, ReservationStatus fromStatus,
            ReservationStatus toStatus, String reason, Instant createdAt) {
        return new ReservationStatusHistory(reservationId, fromStatus, toStatus, reason, createdAt);
    }
}
