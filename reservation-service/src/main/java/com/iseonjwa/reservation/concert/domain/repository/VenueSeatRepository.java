package com.iseonjwa.reservation.concert.domain.repository;

import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;

import java.util.Set;

public interface VenueSeatRepository {
    // 공연장에 실제로 있는 좌석 등급. 회차 좌석 가격이 등급마다 하나씩 있는지 확인할 때 쓴다
    Set<SeatGrade> findGradesByVenueId(Long venueId);
}
