package com.iseonjwa.reservation.seathold.application;

import java.time.Instant;
import java.util.Optional;

/**
 * 선점 만료가 추첨 당첨 예매를 만났을 때 추첨 쪽에 묻고 알리는 통로(D12, ISE-149). 인터페이스는 만료를 가진 seathold에 두고
 * 구현은 lottery가 한다. 그래서 의존 방향이 lottery → seathold 하나로 유지된다(ADR 0034).
 */
public interface LotteryHoldExpiryHook {

    /**
     * 추첨 당첨 예매면 선점을 연장할 수 있는 상한(승계 마감 + 30분)을 돌려준다.
     *
     * @return 추첨 당첨 예매가 아니면 비어 있다
     */
    Optional<Instant> extensionLimit(Long reservationId);

    // 만료한 추첨 예매를 응모에 반영한다. 만료 트랜잭션 안에서 부른다
    void onExpired(Long reservationId);
}
