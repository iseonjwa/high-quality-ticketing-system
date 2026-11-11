package com.iseonjwa.reservation.reservation.presentation.dto.response;

import com.iseonjwa.reservation.reservation.application.ReservationDetailResult;
import com.iseonjwa.reservation.reservation.domain.entity.Reservation;
import com.iseonjwa.reservation.reservation.domain.entity.ReservationStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

@Getter
@AllArgsConstructor
public class ReservationDetailResponse {

    // 응답 시각은 한국 시간 오프셋(+09:00)으로 내린다. 선점 응답과 같다(reservation contracts.md 2-1절)
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private Long reservationId;

    private String reservationNo;

    private Long scheduleId;

    private String status;

    private OffsetDateTime holdExpiresAt;

    private long totalAmount;

    private List<ReservationSeatResponse> seats;

    private String paymentToken;

    private List<TicketResponse> tickets;

    // 확정 전에는 null이다. 환불 상태는 이 ID로 결제 조회 API를 불러 본다
    private Long paymentId;

    public static ReservationDetailResponse from(ReservationDetailResult result) {
        Reservation reservation = result.getReservation();
        List<ReservationSeatResponse> seats = result.getSeats().stream()
                .map(ReservationSeatResponse::from)
                .toList();
        // 티켓은 CONFIRMED일 때만 준다(SA 11-5). 그 밖에는 빈 배열이다
        List<TicketResponse> tickets = reservation.getStatus() == ReservationStatus.CONFIRMED
                ? result.getSeats().stream().filter(seat -> seat.ticketNo() != null).map(TicketResponse::from).toList()
                : List.of();
        return new ReservationDetailResponse(reservation.getId(), reservation.getReservationNo(),
                reservation.getScheduleId(), reservation.getStatus().name(), kst(reservation.getHoldExpiresAt()),
                reservation.getTotalAmount().toBigDecimal().longValueExact(), seats, result.getPaymentToken(), tickets,
                reservation.getPaymentId());
    }

    private static OffsetDateTime kst(Instant instant) {
        return instant == null ? null : instant.atZone(KST).toOffsetDateTime();
    }
}
