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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 좌석 일괄 선점 한 건(SA 12-3). 좌석별 상태는 {@link ReservationSeat}가 가진다.
 *
 * <p>Note: 상태와 선점 만료 시각은 {@code updatable = false}다. 만료 스케줄러·결제 결과 소비자와 같은 행을 두고 경합하므로
 * 변경 감지로 덮어쓰지 않고, 저장소의 조건부 UPDATE("현재 상태가 from일 때만")로만 전이한다(SA 8-4).
 */
@Getter
@Entity
@Table(name = "reservations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_no", nullable = false, updatable = false, length = 20)
    private String reservationNo;

    // 멱등키에서 파생한 접수 ID. seat_hold_requests의 PK와 같은 값이다
    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "schedule_id", nullable = false, updatable = false)
    private Long scheduleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private ReservationStatus status;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(nullable = false, updatable = false)
    private int quantity;

    @Convert(converter = MoneyConverter.class)
    @Column(name = "total_amount", nullable = false, updatable = false, precision = 15, scale = 0)
    private Money totalAmount;

    @Column(name = "hold_expires_at", updatable = false)
    private Instant holdExpiresAt;

    // 승인된 결제 ID. 결제 결과를 반영할 때 조건부 UPDATE로 채운다
    @Column(name = "payment_id", updatable = false)
    private Long paymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private ReservationSource source;

    private Reservation(String reservationNo, UUID requestId, Long userId, Long scheduleId, int quantity,
            Money totalAmount, Instant holdExpiresAt, ReservationSource source) {
        this.reservationNo = reservationNo;
        this.requestId = requestId;
        this.userId = userId;
        this.scheduleId = scheduleId;
        this.status = ReservationStatus.HELD;
        this.quantity = quantity;
        this.totalAmount = totalAmount;
        this.holdExpiresAt = holdExpiresAt;
        this.source = source;
    }

    // 총액은 클라이언트 값이 아니라 선점한 좌석의 가격 합으로 서버가 계산한다
    public static Reservation hold(String reservationNo, UUID requestId, Long userId, Long scheduleId,
            List<Money> seatPrices, Instant holdExpiresAt) {
        if (seatPrices.isEmpty()) {
            throw new IllegalArgumentException("선점할 좌석이 없습니다");
        }
        Money totalAmount = Money.ZERO;
        for (Money price : seatPrices) {
            totalAmount = totalAmount.plus(price);
        }
        return new Reservation(reservationNo, requestId, userId, scheduleId, seatPrices.size(), totalAmount,
                holdExpiresAt, ReservationSource.GENERAL);
    }
}
