package com.iseonjwa.reservation.concert.domain.repository;

import com.iseonjwa.reservation.concert.domain.entity.Concert;

import java.util.Optional;

public interface ConcertRepository {
    Concert save(Concert concert);

    // 지운 공연은 @SQLRestriction 때문에 empty다
    Optional<Concert> findById(Long id);
}