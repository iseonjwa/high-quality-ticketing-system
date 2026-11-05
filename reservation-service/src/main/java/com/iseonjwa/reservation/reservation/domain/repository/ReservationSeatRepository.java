package com.iseonjwa.reservation.reservation.domain.repository;

import com.iseonjwa.reservation.global.vo.Money;
import com.iseonjwa.reservation.reservation.domain.entity.ReservationSeat;

import java.time.Instant;
import java.util.List;

public interface ReservationSeatRepository {
    ReservationSeat save(ReservationSeat seat);

    // 활성 예매에 묶인 좌석이 섞여 있으면 ActiveSeatConflictException을 던진다
    List<ReservationSeat> saveAll(List<ReservationSeat> seats);

    List<ReservationSeat> findByReservationId(Long reservationId);

    // 예매에 묶인 HELD 좌석을 RELEASED로 바꾸고 바꾼 행 수를 돌려준다. 부분 유니크 인덱스에서 빠져 다시 선점할 수 있다(D2)
    int releaseHeld(Long reservationId, Instant now, Long actorId);

    // 예매에 묶인 HELD 좌석을 CONFIRMED로 바꾸고 바꾼 행 수를 돌려준다(결제 승인)
    int confirmHeld(Long reservationId, Instant now);

    // 예매 상세(SA 11-5). 예매 좌석에 회차·공연장 좌석의 표시 정보와 발급된 티켓 번호를 붙여 좌석 ID 순으로 돌려준다
    List<SeatDetailRow> findSeatDetails(Long reservationId);

    // seatId는 회차 좌석 ID(선점 요청의 seatIds와 같은 값), price는 선점 시점 가격, ticketNo는 발급 전이면 null이다
    record SeatDetailRow(long seatId, String section, int rowNo, int seatNo, String grade, Money price,
            String ticketNo) {}
}
