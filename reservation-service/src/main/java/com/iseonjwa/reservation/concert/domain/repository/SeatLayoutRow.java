package com.iseonjwa.reservation.concert.domain.repository;

import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;
import com.iseonjwa.reservation.global.vo.Money;

/**
 * 회차 좌석 배치 한 줄. 회차 좌석을 만든 뒤 바뀌지 않는 값만 담는다(ISE-204 P4b).
 *
 * <p>Note: 회차 좌석 등급은 추첨 설정·수정·종결(D9)로 LOTTERY와 원래 등급 사이를 오가서 넣지 않는다.
 * 넣으면 그때마다 캐시를 비워야 한다. 바뀌는 등급과 상태는 {@link SeatStateRow}로 따로 읽는다.
 *
 * @param sectionGrade 공연장 좌석의 원래 등급. 추첨석이어도 구역 등급은 이 값이다
 */
public record SeatLayoutRow(Long seatId, String section, SeatGrade sectionGrade, Money price, int rowNo, int seatNo) {
}
