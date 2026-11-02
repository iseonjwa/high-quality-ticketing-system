package com.iseonjwa.reservation.lottery.infrastructure.persistence;

import com.iseonjwa.reservation.lottery.domain.Lottery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

interface SpringDataLotteryRepository extends JpaRepository<Lottery, Long> {

    boolean existsByScheduleId(Long scheduleId);

    // 엔티티가 아니라 값만 읽어 영속성 컨텍스트에 추첨이 먼저 올라가지 않게 한다.
    // 그래야 회차 잠금 뒤 findById가 다른 수정이 커밋한 최신 행을 읽는다
    @Query("select l.scheduleId from Lottery l where l.id = :id")
    Optional<Long> findScheduleIdById(@Param("id") Long id);
}
