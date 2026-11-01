package com.iseonjwa.reservation.concert.infrastructure.persistence;

import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;
import com.iseonjwa.reservation.concert.domain.entity.VenueSeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

interface SpringDataVenueSeatRepository extends JpaRepository<VenueSeat, Long> {

    @Query("select distinct vs.grade from VenueSeat vs where vs.venue.id = :venueId and vs.deletedAt is null")
    List<SeatGrade> findGradesByVenueId(@Param("venueId") Long venueId);
}
