package com.iseonjwa.reservation.lottery.domain;

import java.util.List;
import java.util.Objects;

/**
 * 회차 좌석 중 등급이 {@code LOTTERY}인, 배정 계산에 넘기는 추첨석 한 자리(D9).
 *
 * @param seatId  배정 순서("순위가 높을수록 ID가 작은 좌석")의 기준인 {@code schedule_seats}의 ID
 * @param section 구역({@code schedule_seats.section})
 * @param rowNo   열 번호({@code venue_seats.row_no})
 * @param seatNo  열 안의 좌석 번호({@code venue_seats.seat_no})
 */
public record LotterySeat(long seatId, String section, int rowNo, int seatNo) {

    public LotterySeat {
        Objects.requireNonNull(section);
    }

    /**
     * 두 좌석이 나란히 붙어 있는지 판정한다. 2매 당첨자에게 옆자리 쌍을 먼저 주는 데 쓴다.
     */
    // Note: 좌석 ID는 열이 바뀌어도 이어지므로(1열 끝 10, 2열 첫 자리 11) ID 차이가 아니라 같은 구역·열의 좌석 번호 차이로 판정한다(D9).
    public boolean isNextTo(LotterySeat other) {
        return neighborPositions().contains(other.position());
    }

    public SeatPosition position() {
        return new SeatPosition(section, rowNo, seatNo);
    }

    // 옆자리 판정을 이 메서드 한 곳에서 정하는, 옆자리가 될 수 있는 두 위치(같은 구역·열의 왼쪽·오른쪽 한 칸).
    public List<SeatPosition> neighborPositions() {
        return List.of(
                new SeatPosition(section, rowNo, seatNo - 1),
                new SeatPosition(section, rowNo, seatNo + 1));
    }

    /**
     * 좌석 ID와 달리 실제 배치를 나타내 옆자리를 찾는 색인 키로 쓰는, 공연장 안의 좌석 위치.
     */
    public record SeatPosition(String section, int rowNo, int seatNo) {
    }
}
