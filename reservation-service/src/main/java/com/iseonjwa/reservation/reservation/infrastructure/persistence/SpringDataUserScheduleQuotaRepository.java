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
