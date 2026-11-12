package com.iseonjwa.reservation.reservation.domain.repository;

import com.iseonjwa.reservation.reservation.domain.entity.UserScheduleQuota;
import com.iseonjwa.reservation.reservation.domain.entity.UserScheduleQuotaId;

import java.time.Instant;
import java.util.Optional;

public interface UserScheduleQuotaRepository {
    Optional<UserScheduleQuota> findById(UserScheduleQuotaId id);

    // 행이 없으면 quantity로 만들고, 있으면 held_count에 quantity를 더한다. 한도 검사는 하지 않는다
    void addHeldCount(UserScheduleQuotaId id, int quantity, Instant now, Long actorId);

    // 행이 없으면 0으로 만든 뒤, held_count + quantity가 한도 이하일 때만 더한다(SA 12-7 ①, D1).
    // 0을 돌려주면 한도 초과다. 행 락에서 같은 사용자의 동시 요청이 줄을 서서 검사와 증가 사이에 끼어들 수 없다
    int addHeldCountWithinLimit(UserScheduleQuotaId id, int quantity, int maxPerUser, Instant now, Long actorId);

    // held_count가 quantity 이상일 때만 빼고 바꾼 행 수를 돌려준다. 0이면 행이 없거나 보유 매수가 모자란 것이다
    int subtractHeldCount(UserScheduleQuotaId id, int quantity, Instant now, Long actorId);
}
