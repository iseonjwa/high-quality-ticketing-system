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
}
