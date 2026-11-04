package com.iseonjwa.reservation.reservation.domain.repository;

import com.iseonjwa.reservation.reservation.domain.entity.Reservation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository {
    Reservation save(Reservation reservation);

    Optional<Reservation> findById(Long id);

    Optional<Reservation> findByRequestId(UUID requestId);

    // 대사(ADR 0029): ID 목록의 예매를 ID 순으로 돌려준다. 없는 ID는 빠진다
    List<Reservation> findAllByIds(List<Long> ids);

    // 대사(ADR 0029): updated_at이 [from, to)이고 CONFIRMED·CANCELED인 예매를 cursor 다음 ID부터 limit건까지 ID 순으로 돌려준다
    List<Reservation> findSettledChanges(Instant from, Instant to, long cursor, int limit);

    // 선점 기한(hold_expires_at)이 now 이전인 HELD 예매 ID를 기한이 이른 순으로 limit건까지 돌려준다
    List<Long> findExpiredHeldIds(Instant now, int limit);

    // HELD이고 기한이 지난 예매만 EXPIRED로 바꾸고 바꾼 행 수를 돌려준다. 0이면 다른 쪽이 먼저 상태를 바꾼 것이다
    int expireHeld(Long reservationId, Instant now, Long actorId);

    // HELD인 예매만 CONFIRMED로 바꾸고 결제 ID를 남긴다. 0이면 만료 스케줄러 등이 먼저 상태를 바꾼 것이다
    int confirmHeld(Long reservationId, Long paymentId, Instant now);

    // HELD인 예매만 RELEASED로 바꾼다(결제 거절). 0이면 다른 쪽이 먼저 상태를 바꾼 것이다
    int releaseHeld(Long reservationId, Instant now);

    /**
     * 기한이 지난 추첨 예매의 선점을 한 번만 30분 연장한다(D12, 결제 결과를 받지 못한 만료). 상한을 넘기지 않는다.
     *
     * @return 연장한 행 수. 이미 연장했거나 상한이 지났거나 HELD가 아니면 0
     */
    int extendLotteryHoldOnce(Long reservationId, Instant now, Instant limit, Long actorId);
}
