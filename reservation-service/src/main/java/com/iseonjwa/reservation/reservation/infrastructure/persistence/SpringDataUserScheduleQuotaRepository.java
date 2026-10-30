package com.iseonjwa.reservation.reservation.infrastructure.persistence;

import com.iseonjwa.reservation.reservation.domain.entity.UserScheduleQuota;
import com.iseonjwa.reservation.reservation.domain.entity.UserScheduleQuotaId;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataUserScheduleQuotaRepository extends JpaRepository<UserScheduleQuota, UserScheduleQuotaId> {}
