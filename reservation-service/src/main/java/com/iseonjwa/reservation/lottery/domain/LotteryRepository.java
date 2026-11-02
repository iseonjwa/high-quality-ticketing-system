package com.iseonjwa.reservation.lottery.domain;

public interface LotteryRepository {
    Lottery save(Lottery lottery);

    // 지운 추첨도 센다. UNIQUE(schedule_id)가 지운 행까지 막기 때문이다
    boolean existsByScheduleId(Long scheduleId);
}
