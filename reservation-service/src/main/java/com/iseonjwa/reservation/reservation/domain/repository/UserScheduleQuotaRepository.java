package com.iseonjwa.reservation.reservation.domain.repository;

import com.iseonjwa.reservation.reservation.domain.entity.UserScheduleQuota;
import com.iseonjwa.reservation.reservation.domain.entity.UserScheduleQuotaId;

import java.time.Instant;
import java.util.Optional;

public interface UserScheduleQuotaRepository {
    Optional<UserScheduleQuota> findById(UserScheduleQuotaId id);

    // 행이 없으면 quantity로 만들고, 있으면 held_count에 quantity를 더한다. 한도 검사는 하지 않는다
    void addHeldCount(UserScheduleQuotaId id, int quantity, Instant now, Long actorId);
}
