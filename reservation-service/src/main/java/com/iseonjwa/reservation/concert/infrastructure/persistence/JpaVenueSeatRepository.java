package com.iseonjwa.reservation.concert.infrastructure.persistence;

import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;
import com.iseonjwa.reservation.concert.domain.repository.VenueSeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.EnumSet;
import java.util.Set;

@Repository
@RequiredArgsConstructor
public class JpaVenueSeatRepository implements VenueSeatRepository {

    private final SpringDataVenueSeatRepository springDataVenueSeatRepository;

    @Override
    public Set<SeatGrade> findGradesByVenueId(Long venueId) {
        Set<SeatGrade> grades = EnumSet.noneOf(SeatGrade.class);
        grades.addAll(springDataVenueSeatRepository.findGradesByVenueId(venueId));
        return grades;
    }
}
