package com.iseonjwa.reservation.concert.infrastructure.persistence;

import com.iseonjwa.reservation.concert.domain.entity.Concert;
import com.iseonjwa.reservation.concert.domain.repository.ConcertRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaConcertRepository implements ConcertRepository {

    private final SpringDataConcertRepository springDataConcertRepository;

    @Override
    public Concert save(Concert concert) {
        return springDataConcertRepository.save(concert);
    }

    @Override
    public Optional<Concert> findById(Long id) {
        return springDataConcertRepository.findById(id);
    }
}