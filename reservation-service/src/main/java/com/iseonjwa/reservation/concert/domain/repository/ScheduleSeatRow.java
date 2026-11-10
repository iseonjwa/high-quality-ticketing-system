package com.iseonjwa.reservation.concert.domain.repository;

import com.iseonjwa.reservation.concert.domain.entity.ScheduleSeat;
import com.iseonjwa.reservation.concert.domain.entity.ScheduleSeatStatus;
import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;
import com.iseonjwa.reservation.global.vo.Money;

/**
 * 회차 좌석 조회(SA 6-5)의 좌석 한 줄. 응답에 필요한 열만 읽는다.
 *
 * <p>Note: 엔티티로 읽으면 좌석마다 회차 좌석·공연장 좌석 엔티티 2개가 생기고 트랜잭션이 끝날 때까지 영속성 컨텍스트에 남는다.
 * 15,000석이면 엔티티 30,000개라 부하가 몰리면 GC와 커넥션 점유 시간이 늘었다(ISE-189). 좌석마다 이 객체 하나만 만든다.
 *
 * @param sectionGrade 공연장 좌석의 원래 등급. 추첨석이어도 구역 등급은 이 값이다
 * @param grade        회차 좌석 등급. 추첨석이면 LOTTERY다
 */
public record ScheduleSeatRow(Long seatId, String section, SeatGrade sectionGrade, Money price,
                              int rowNo, int seatNo, SeatGrade grade, ScheduleSeatStatus status) {

    public boolean isHoldable() {
        return ScheduleSeat.isHoldable(status, grade);
    }
}
