package com.iseonjwa.reservation.seathold.application;

import java.util.List;

/**
 * 선점 트랜잭션을 열기 전에 "이미 잡힌 좌석이 섞였는가"를 DB 없이 묻는 포트(ISE-281).
 * true면 확실히 질 요청으로 보고 트랜잭션 없이 409로 끝낸다. 판정이 아니라 거절에만 쓰므로,
 * 모르면(캐시가 없거나 낡음) false를 돌려 지금처럼 DB의 조건부 UPDATE가 판정하게 한다.
 */
public interface HeldSeatPreCheck {

    boolean anyNotHoldable(Long scheduleId, List<Long> seatIds);
}
