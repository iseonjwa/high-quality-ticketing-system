package com.iseonjwa.reservation.reservation.domain.repository;

import com.iseonjwa.reservation.reservation.domain.entity.UserScheduleQuota;
import com.iseonjwa.reservation.reservation.domain.entity.UserScheduleQuotaId;

import java.util.Optional;

public interface UserScheduleQuotaRepository {
    Optional<UserScheduleQuota> findById(UserScheduleQuotaId id);
}
