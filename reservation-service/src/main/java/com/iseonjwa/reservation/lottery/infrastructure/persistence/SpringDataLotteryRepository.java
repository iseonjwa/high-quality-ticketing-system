package com.iseonjwa.reservation.lottery.infrastructure.persistence;

import com.iseonjwa.reservation.lottery.domain.Lottery;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataLotteryRepository extends JpaRepository<Lottery, Long> {

    boolean existsByScheduleId(Long scheduleId);
}
