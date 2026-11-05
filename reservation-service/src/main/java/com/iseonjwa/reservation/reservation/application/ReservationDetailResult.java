package com.iseonjwa.reservation.reservation.application;

import com.iseonjwa.reservation.reservation.domain.entity.Reservation;
import com.iseonjwa.reservation.reservation.domain.repository.ReservationSeatRepository.SeatDetailRow;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

// 예매 상세(SA 11-5). 예매, 좌석별 표시 정보·티켓 번호, 결제 토큰을 묶는다
@Getter
@AllArgsConstructor
public class ReservationDetailResult {

    private Reservation reservation;

    // 좌석 ID 오름차순
    private List<SeatDetailRow> seats;

    // HELD이고 결제 토큰 만료(holdExpiresAt - 10초) 전일 때만 채운다. 그 밖에는 null이다
    private String paymentToken;
}