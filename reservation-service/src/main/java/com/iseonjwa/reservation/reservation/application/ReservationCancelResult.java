package com.iseonjwa.reservation.reservation.application;

// 예매 취소 결과. 화면은 paymentId로 결제 조회 API를 불러 환불 상태를 본다. 취소 요청 시점에 결제 ID가 없을 수는 없지만 null을 허용한다
public record ReservationCancelResult(Long reservationId, Long paymentId) {}
