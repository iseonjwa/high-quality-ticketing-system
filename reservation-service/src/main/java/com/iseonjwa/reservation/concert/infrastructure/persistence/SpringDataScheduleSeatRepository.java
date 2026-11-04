package com.iseonjwa.reservation.concert.infrastructure.persistence;

import com.iseonjwa.reservation.concert.domain.entity.ScheduleSeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

interface SpringDataScheduleSeatRepository extends JpaRepository<ScheduleSeat, Long> {

    boolean existsByScheduleId(Long scheduleId);

    // join fetch로 공연장 좌석을 한 쿼리에 같이 읽는다. 좌석마다 공연장 좌석을 따로 조회하는 N+1을 막는다.
    // ScheduleSeat에는 @SQLRestriction이 없어서 지운 좌석은 조건으로 거른다
    @Query("select ss from ScheduleSeat ss join fetch ss.venueSeat vs where ss.schedule.id = :scheduleId"
            + " and ss.deletedAt is null order by ss.section asc, vs.rowNo asc, vs.seatNo asc")
    List<ScheduleSeat> findByScheduleIdWithVenueSeat(@Param("scheduleId") Long scheduleId);

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

    // Note: 고를 좌석에 FOR UPDATE를 걸어, 같은 좌석을 선점하는 트랜잭션과 엇갈려도 이미 HELD가 된 좌석은 바꾸지 않는다.
    // 선점이 먼저 끝나면 다시 읽은 행이 AVAILABLE이 아니어서 빠지고, 바꾼 수가 모자라면 호출한 쪽이 되돌린다.
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE schedule_seats
            SET grade = 'LOTTERY', updated_at = now(), updated_by = CAST(:actorId AS BIGINT)
            WHERE id IN (
                SELECT id FROM schedule_seats
                WHERE schedule_id = :scheduleId AND grade = :sourceGrade
                  AND status = 'AVAILABLE' AND deleted_at IS NULL
                ORDER BY id
                LIMIT :seatCount
                FOR UPDATE)
            """)
    int updateGradeToLottery(@Param("scheduleId") Long scheduleId, @Param("sourceGrade") String sourceGrade,
                             @Param("seatCount") int seatCount, @Param("actorId") Long actorId);

    // ID가 큰 추첨석부터 되돌려, 남는 추첨석이 ID 순으로 이어지게 한다(옆자리 배정에 유리, D9)
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE schedule_seats
            SET grade = :sourceGrade, updated_at = now(), updated_by = CAST(:actorId AS BIGINT)
            WHERE id IN (
                SELECT id FROM schedule_seats
                WHERE schedule_id = :scheduleId AND grade = 'LOTTERY' AND deleted_at IS NULL
                ORDER BY id DESC
                LIMIT :seatCount
                FOR UPDATE)
            """)
    int updateGradeFromLottery(@Param("scheduleId") Long scheduleId, @Param("sourceGrade") String sourceGrade,
                               @Param("seatCount") int seatCount, @Param("actorId") Long actorId);

    // HELD·SOLD 추첨석도 등급만 되돌린다. 팔린 추첨석은 reservations.source로 구분하고,
    // 종결 뒤 취소·만료로 풀린 좌석은 원래 등급으로 일반 판매에 나온다(D9)
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE schedule_seats
            SET grade = :sourceGrade, updated_at = now(), updated_by = CAST(:actorId AS BIGINT)
            WHERE schedule_id = :scheduleId AND grade = 'LOTTERY' AND deleted_at IS NULL
            """)
    int updateAllGradeFromLottery(@Param("scheduleId") Long scheduleId, @Param("sourceGrade") String sourceGrade,
                                  @Param("actorId") Long actorId);

    // 결제 결과 반영(SA 8-3). HELD인 좌석만 바꾸므로 이미 다른 상태가 된 좌석은 건드리지 않는다.
    // 소비자가 바꾼 행은 사람 감사자가 없어 updated_by를 비운다
    @Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE schedule_seats
            SET status = :toStatus, updated_at = :now, updated_by = NULL
            WHERE schedule_id = :scheduleId AND id IN (:seatIds) AND status = 'HELD'
            """)
    int updateHeldStatus(@Param("scheduleId") Long scheduleId, @Param("seatIds") List<Long> seatIds,
                         @Param("toStatus") String toStatus, @Param("now") Instant now);

    @Query(nativeQuery = true, value = "SELECT count(*) FROM schedule_seats WHERE grade = 'LOTTERY' AND deleted_at IS NULL")
    long countAllLotterySeats();

    @Query(nativeQuery = true, value = """
            SELECT ss.id, ss.section, vs.row_no, vs.seat_no
            FROM schedule_seats ss JOIN venue_seats vs ON vs.id = ss.venue_seat_id
            WHERE ss.schedule_id = :scheduleId AND ss.grade = 'LOTTERY' AND ss.deleted_at IS NULL
            ORDER BY ss.id
            """)
    List<Object[]> findLotterySeatRows(@Param("scheduleId") Long scheduleId);

    @Query(nativeQuery = true, value = """
            SELECT ss.id, ss.section, vs.row_no, vs.seat_no
            FROM schedule_seats ss JOIN venue_seats vs ON vs.id = ss.venue_seat_id
            WHERE ss.schedule_id = :scheduleId AND ss.grade = 'LOTTERY' AND ss.status = 'AVAILABLE'
              AND ss.deleted_at IS NULL
            ORDER BY ss.id
            """)
    List<Object[]> findAvailableLotterySeatRows(@Param("scheduleId") Long scheduleId);
}
