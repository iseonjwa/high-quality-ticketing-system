package com.iseonjwa.reservation.concert.presentation.dto.response;

import com.iseonjwa.reservation.concert.domain.entity.ScheduleSeat;
import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleSeatRepository.SeatMapRow;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

// 좌석 조회 응답의 구역 하나. 한 구역은 한 등급·한 가격이다(공연장 시드, 등급별 가격)
@Getter
@AllArgsConstructor
public class SeatSectionResponse {

    private String section;

    // 공연장 좌석의 원래 등급이다. 회차 좌석 등급은 추첨석이면 LOTTERY로 바뀌어 구역 등급으로 쓰지 않는다
    private SeatGrade grade;

    private BigDecimal price;

    private List<SeatResponse> seats;

    // 구역의 첫 좌석으로 구역 정보를 만든다. 추첨석도 원래 등급 가격을 그대로 갖는다
    public static SeatSectionResponse from(ScheduleSeat seat) {
        return new SeatSectionResponse(seat.getSection(), seat.getVenueSeat().getGrade(),
                seat.getPrice().toBigDecimal(), new ArrayList<>());
    }

    public void add(ScheduleSeat seat) {
        seats.add(SeatResponse.from(seat));
    }

    public static SeatSectionResponse from(SeatMapRow row) {
        return new SeatSectionResponse(row.layout().section(), row.layout().sectionGrade(), row.layout().price(),
                new ArrayList<>());
    }

    public void add(SeatMapRow row) {
        seats.add(SeatResponse.from(row));
    }
}
