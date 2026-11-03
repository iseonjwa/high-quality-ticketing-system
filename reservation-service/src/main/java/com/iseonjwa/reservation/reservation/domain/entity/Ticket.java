package com.iseonjwa.reservation.reservation.domain.entity;

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

/**
 * 예매 좌석 한 개의 티켓(SA 12-3). 예매가 CONFIRMED가 될 때 좌석마다 한 장 발급한다.
 */
@Getter
@Entity
@Table(name = "tickets")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Ticket extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_no", nullable = false, updatable = false, length = 20)
    private String ticketNo;

    @Column(name = "reservation_seat_id", nullable = false, updatable = false)
    private Long reservationSeatId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TicketStatus status;

    private Ticket(String ticketNo, Long reservationSeatId) {
        this.ticketNo = ticketNo;
        this.reservationSeatId = reservationSeatId;
        this.status = TicketStatus.ISSUED;
    }

    public static Ticket issue(String ticketNo, Long reservationSeatId) {
        return new Ticket(ticketNo, reservationSeatId);
    }
}
