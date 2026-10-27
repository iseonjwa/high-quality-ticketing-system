/**
 * 공연·회차·좌석 조회 응용 계층: 유스케이스와 트랜잭션 경계.
 * domain 계층만 의존한다. Kafka·Redis 같은 외부 기술 코드를 두지 않는다.
 */
package com.iseonjwa.reservation.concert.application;
