package com.iseonjwa.reservation.seathold.domain.repository;

/**
 * 같은 입장 토큰으로 거절되지 않은 선점 요청이 이미 있어 저장하지 못했다(부분 유니크 인덱스, ADR 0053).
 * 저장소 구현이 DB 제약 위반을 이 예외로 바꿔 던져, 같은 멱등키 경합(PK 위반)과 구분하게 한다.
 */
public class EntryTokenAlreadyUsedException extends RuntimeException {
    public EntryTokenAlreadyUsedException(Throwable cause) {
        super("이미 좌석 선점에 쓴 입장 토큰입니다", cause);
    }
}
