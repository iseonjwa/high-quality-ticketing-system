package com.iseonjwa.reservation.concert.infrastructure.persistence;

import com.iseonjwa.reservation.concert.domain.entity.Schedule;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

interface SpringDataScheduleRepository extends JpaRepository<Schedule, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Schedule s where s.id = :id")
    Optional<Schedule> findByIdForUpdate(@Param("id") Long id);
}
