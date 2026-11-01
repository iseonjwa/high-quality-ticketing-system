package com.iseonjwa.reservation.concert.infrastructure.persistence;

import com.iseonjwa.reservation.concert.domain.entity.ScheduleSeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;

interface SpringDataScheduleSeatRepository extends JpaRepository<ScheduleSeat, Long> {

    boolean existsByScheduleId(Long scheduleId);

    // Note: 15,000석을 엔티티로 하나씩 save하면 INSERT가 15,000번 나간다.
    // DB 안에서 venue_seats를 읽어 바로 넣는 INSERT ... SELECT 한 문장으로 만든다.
    // JPA 감사 리스너를 거치지 않으므로 created_at·created_by를 직접 채운다.
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO schedule_seats (schedule_id, venue_seat_id, section, grade, price, status,
                                        created_at, created_by, updated_at, updated_by)
            SELECT :scheduleId, vs.id, vs.section, vs.grade,
                   CASE vs.grade WHEN 'VIP' THEN :vipPrice WHEN 'R' THEN :rPrice WHEN 'S' THEN :sPrice END,
                   'AVAILABLE', now(), CAST(:actorId AS BIGINT), now(), CAST(:actorId AS BIGINT)
            FROM venue_seats vs
            WHERE vs.venue_id = :venueId AND vs.deleted_at IS NULL
            """)
    int insertFromVenueSeats(@Param("scheduleId") Long scheduleId, @Param("venueId") Long venueId,
                             @Param("vipPrice") BigDecimal vipPrice, @Param("rPrice") BigDecimal rPrice,
                             @Param("sPrice") BigDecimal sPrice, @Param("actorId") Long actorId);
}
