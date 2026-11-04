package com.iseonjwa.reservation.concert.domain.repository;

import com.iseonjwa.reservation.concert.domain.entity.Venue;

import java.util.Optional;

public interface VenueRepository {
    // 지운 공연장은 @SQLRestriction 때문에 empty다
    Optional<Venue> findById(Long id);
}
