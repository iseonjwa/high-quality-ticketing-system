package com.iseonjwa.reservation.reservation.domain.repository;

/**
 * 같은 회차 좌석에 활성(HELD·CONFIRMED) 예매 좌석이 이미 있어 저장하지 못했다(D2 부분 유니크 인덱스).
 * 저장소 구현이 DB 제약 위반을 이 예외로 바꿔 던져, 호출하는 쪽이 Spring 데이터 예외에 의존하지 않게 한다.
 */
public class ActiveSeatConflictException extends RuntimeException {
    public ActiveSeatConflictException(Throwable cause) {
        super("이미 활성 예매에 묶인 좌석입니다", cause);
    }
}
