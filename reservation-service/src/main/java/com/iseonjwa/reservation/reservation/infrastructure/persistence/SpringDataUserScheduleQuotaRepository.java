package com.iseonjwa.reservation.reservation.infrastructure.persistence;

import com.iseonjwa.reservation.reservation.domain.entity.UserScheduleQuota;
import com.iseonjwa.reservation.reservation.domain.entity.UserScheduleQuotaId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

interface SpringDataUserScheduleQuotaRepository extends JpaRepository<UserScheduleQuota, UserScheduleQuotaId> {
    @Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            INSERT INTO user_schedule_quotas (user_id, schedule_id, held_count, created_at, created_by, updated_at, updated_by)
            VALUES (:userId, :scheduleId, :quantity, :now, :actorId, :now, :actorId)
            ON CONFLICT (user_id, schedule_id) DO UPDATE
               SET held_count = user_schedule_quotas.held_count + EXCLUDED.held_count,
                   updated_at = EXCLUDED.updated_at,
                   updated_by = EXCLUDED.updated_by
            """)
    int addHeldCount(@Param("userId") Long userId, @Param("scheduleId") Long scheduleId,
            @Param("quantity") int quantity, @Param("now") Instant now, @Param("actorId") Long actorId);

    // 같은 사용자의 동시 요청이 함께 INSERT해도 한쪽은 상대 커밋을 기다린 뒤 DO NOTHING이 된다
    @Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            INSERT INTO user_schedule_quotas (user_id, schedule_id, held_count, created_at, created_by, updated_at, updated_by)
            VALUES (:userId, :scheduleId, 0, :now, :actorId, :now, :actorId)
            ON CONFLICT (user_id, schedule_id) DO NOTHING
            """)
    int insertEmptyIfAbsent(@Param("userId") Long userId, @Param("scheduleId") Long scheduleId,
            @Param("now") Instant now, @Param("actorId") Long actorId);

    // Note: 한도 검사를 WHERE에 둔다. 먼저 커밋한 요청이 held_count를 바꾸면 기다리던 요청은 바뀐 값으로 조건을 다시 평가한다
    @Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE user_schedule_quotas
               SET held_count = held_count + :quantity, updated_at = :now, updated_by = :actorId
             WHERE user_id = :userId
               AND schedule_id = :scheduleId
               AND held_count + :quantity <= :maxPerUser
            """)
    int addHeldCountWithinLimit(@Param("userId") Long userId, @Param("scheduleId") Long scheduleId,
            @Param("quantity") int quantity, @Param("maxPerUser") int maxPerUser, @Param("now") Instant now,
            @Param("actorId") Long actorId);

    // 조건을 WHERE에 둬서 held_count가 음수가 되는 대신 0행이 된다(chk_user_schedule_quotas_held_count)
    @Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE user_schedule_quotas
               SET held_count = held_count - :quantity, updated_at = :now, updated_by = :actorId
             WHERE user_id = :userId
               AND schedule_id = :scheduleId
               AND held_count >= :quantity
            """)
    int subtractHeldCount(@Param("userId") Long userId, @Param("scheduleId") Long scheduleId,
            @Param("quantity") int quantity, @Param("now") Instant now, @Param("actorId") Long actorId);
}
