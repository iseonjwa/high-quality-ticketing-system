/**
 * 좌석 선점 도메인 계층: 선점 접수 요청(SeatHoldRequest)과 저장소 인터페이스.
 * 예매·예매 좌석·사용자 회차별 보유 매수 엔티티는 reservation 모듈이 가진다(ADR 0033). JPA 애너테이션 외에 외부 기술에 의존하지 않는다.
 */
package com.iseonjwa.reservation.seathold.domain;
