/**
 * 좌석 선점 응용 계층: 동기형·접수형 선점 유스케이스와 트랜잭션 경계.
 * 매수 한도 → 좌석 조건부 UPDATE → 예매 저장이 한 트랜잭션이어야 해서(D1·D2) reservation 모듈의 도메인을 직접 호출한다.
 * Kafka·Redis 같은 외부 기술 코드를 두지 않는다.
 */
package com.iseonjwa.reservation.seathold.application;
