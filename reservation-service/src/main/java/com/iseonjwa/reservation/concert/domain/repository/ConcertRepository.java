package com.iseonjwa.reservation.concert.domain.repository;

import com.iseonjwa.reservation.concert.domain.entity.Concert;

import java.util.List;
import java.util.Optional;

public interface ConcertRepository {
    Concert save(Concert concert);

    // 지운 공연은 @SQLRestriction 때문에 empty다
    Optional<Concert> findById(Long id);

    // 최근 등록 순(id 내림차순) 한 페이지. page는 0부터 센다
    List<Concert> findPage(int page, int size);

    long count();
}
