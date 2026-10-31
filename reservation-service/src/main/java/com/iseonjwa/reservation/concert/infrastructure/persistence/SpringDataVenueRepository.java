package com.iseonjwa.reservation.concert.infrastructure.persistence;

import com.iseonjwa.reservation.concert.domain.entity.Venue;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataVenueRepository extends JpaRepository<Venue, Long> {
}
