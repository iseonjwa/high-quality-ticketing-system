package com.iseonjwa.reservation.concert.infrastructure.persistence;

import com.iseonjwa.reservation.concert.domain.entity.Concert;
import com.iseonjwa.reservation.concert.domain.repository.ConcertRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.List;
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

    @Override
    public List<Concert> findPage(int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        return springDataConcertRepository.findAll(pageRequest).getContent();
    }

    @Override
    public long count() {
        return springDataConcertRepository.count();
    }
}
