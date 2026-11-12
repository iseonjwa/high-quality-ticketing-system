package com.iseonjwa.payment.reconciliation.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 한 회차에 예매 서비스에서 가져온 스냅숏 묶음. RC-1~RC-6이 같이 쓴다.
 *
 * @param requestedIds 묶음 조회로 물어본 예매 ID. 여기에 있는데 {@code byId}에 없으면 예매 서비스에 그 예매가 없다는 뜻이다
 * @param byId         묶음 조회로 받은 예매
 * @param changes      이번 회차에 읽은 변경 목록 항목({@code CONFIRMED}·{@code CANCELED}로 바뀐 예매의 현재 상태). 회차 예산을
 *                     넘는 구간은 다음 회차에 읽으므로 기간 전체가 아닐 수 있다
 * @param next         그 회차의 규칙이 모두 판단을 마치면 확정할 다음 진행 상태. 진행을 따로 추적하지 않으면 null
 */
public record ReservationView(Set<Long> requestedIds, Map<Long, ReservationSnapshot> byId,
        List<ReservationSnapshot> changes, ChangeFeedProgress.State next) {
    public ReservationView(Set<Long> requestedIds, Map<Long, ReservationSnapshot> byId,
            List<ReservationSnapshot> changes) {
        this(requestedIds, byId, changes, null);
    }

    /** 이번 회차에 물어본 예매인가. 아니면 준비 뒤에 생긴 대상이라 판단을 미룬다. */
    public boolean wasRequested(long reservationId) {
        return requestedIds.contains(reservationId);
    }

    /** 물어봤는데 없으면 null이다. */
    public ReservationSnapshot find(long reservationId) {
        return byId.get(reservationId);
    }
}
