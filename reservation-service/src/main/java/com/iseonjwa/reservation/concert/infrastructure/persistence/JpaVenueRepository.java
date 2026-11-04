package com.iseonjwa.reservation.concert.infrastructure.persistence;

import com.iseonjwa.reservation.concert.domain.entity.Venue;
import com.iseonjwa.reservation.concert.domain.repository.VenueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaVenueRepository implements VenueRepository {

    private final SpringDataVenueRepository springDataVenueRepository;

    @Override
    public Optional<Venue> findById(Long id) {
        return springDataVenueRepository.findById(id);
    }
}
