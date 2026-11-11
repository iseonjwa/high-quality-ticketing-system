package com.iseonjwa.reservation.reservation.domain.repository;

import com.iseonjwa.reservation.reservation.domain.entity.Ticket;

import java.time.Instant;
import java.util.List;

public interface TicketRepository {
    List<Ticket> saveAll(List<Ticket> tickets);

    // 예매에 묶인 좌석의 ISSUED 티켓을 VOID로 바꾸고 바꾼 장 수를 돌려준다(예매 취소)
    int voidIssuedByReservation(Long reservationId, Instant now, Long actorId);
}
