package com.iseonjwa.reservation.reservation.application;

import com.iseonjwa.reservation.reservation.domain.repository.ReservationRepository.MyReservationRow;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

// 내 예매 목록 한 페이지(SA 11-5)
@Getter
@AllArgsConstructor
public class ReservationListResult {

    // 최근 예매부터
    private List<MyReservationRow> reservations;

    private int page;

    private int size;

    private long totalElements;
}
