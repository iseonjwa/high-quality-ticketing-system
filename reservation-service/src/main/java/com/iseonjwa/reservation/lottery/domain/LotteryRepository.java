package com.iseonjwa.reservation.lottery.domain;

import java.util.Optional;

public interface LotteryRepository {
    Lottery save(Lottery lottery);

    // 지운 추첨도 센다. UNIQUE(schedule_id)가 지운 행까지 막기 때문이다
    boolean existsByScheduleId(Long scheduleId);

    Optional<Lottery> findById(Long id);

    // 수정 때 회차 행을 먼저 잠그려고 추첨 엔티티를 읽기 전에 회차 ID만 꺼낸다
    Optional<Long> findScheduleIdById(Long id);
}
