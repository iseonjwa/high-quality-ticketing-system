package com.iseonjwa.reservation.concert.infrastructure.persistence;

import com.iseonjwa.reservation.concert.domain.entity.Concert;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataConcertRepository extends JpaRepository<Concert, Long> {
}