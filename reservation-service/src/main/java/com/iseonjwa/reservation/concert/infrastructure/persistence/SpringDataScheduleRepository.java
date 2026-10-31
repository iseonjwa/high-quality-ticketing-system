package com.iseonjwa.reservation.concert.infrastructure.persistence;

import com.iseonjwa.reservation.concert.domain.entity.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataScheduleRepository extends JpaRepository<Schedule, Long> {
}
