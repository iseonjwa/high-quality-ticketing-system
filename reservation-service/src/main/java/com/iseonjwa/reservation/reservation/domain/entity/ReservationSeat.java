package com.iseonjwa.reservation.reservation.domain.entity;

import com.iseonjwa.common.persistence.BaseEntity;
import com.iseonjwa.reservation.global.vo.Money;
import com.iseonjwa.reservation.global.vo.MoneyConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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

/**
 * 예매에 묶인 좌석 한 개. 판매 여부의 기준이다(D2).
 *
 * <p>Note: DB의 부분 유니크 인덱스 {@code UNIQUE(schedule_seat_id) WHERE status IN ('HELD','CONFIRMED')}가
 * 좌석 조건부 UPDATE를 비켜 간 중복 선점을 마지막에 막는다. 상태는 예매와 같은 이유로 조건부 UPDATE로만 바꾼다.
 */
@Getter
@Entity
@Table(name = "reservation_seats")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationSeat extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_id", nullable = false, updatable = false)
    private Long reservationId;

    // concert 모듈의 schedule_seats를 가리킨다. 모듈 간 엔티티 결합을 피하려고 ID 값만 둔다
    @Column(name = "schedule_seat_id", nullable = false, updatable = false)
    private Long scheduleSeatId;

    // 선점 시점의 가격. 이후 회차 좌석 가격이 바뀌어도 이 예매의 금액은 바뀌지 않는다
    @Convert(converter = MoneyConverter.class)
    @Column(nullable = false, updatable = false, precision = 15, scale = 0)
    private Money price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private ReservationSeatStatus status;

    private ReservationSeat(Long reservationId, Long scheduleSeatId, Money price) {
        this.reservationId = reservationId;
        this.scheduleSeatId = scheduleSeatId;
        this.price = price;
        this.status = ReservationSeatStatus.HELD;
    }

    public static ReservationSeat hold(Long reservationId, Long scheduleSeatId, Money price) {
        return new ReservationSeat(reservationId, scheduleSeatId, price);
    }
}
