package com.iseonjwa.payment.reconciliation.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 한 회차에 예매 서비스에서 가져온 스냅숏 묶음. RC-1~RC-6이 같이 쓴다.
 *
 * @param requestedIds 묶음 조회로 물어본 예매 ID. 여기에 있는데 {@code byId}에 없으면 예매 서비스에 그 예매가 없다는 뜻이다
 * @param byId         묶음 조회로 받은 예매
 * @param changes      기간 안에 {@code CONFIRMED}·{@code CANCELED}로 바뀐 예매(변경 목록, 현재 상태)
 */
public record ReservationView(Set<Long> requestedIds, Map<Long, ReservationSnapshot> byId,
        List<ReservationSnapshot> changes) {
    /** 이번 회차에 물어본 예매인가. 아니면 준비 뒤에 생긴 대상이라 판단을 미룬다. */
    public boolean wasRequested(long reservationId) {
        return requestedIds.contains(reservationId);
    }

    /** 물어봤는데 없으면 null이다. */
    public ReservationSnapshot find(long reservationId) {
        return byId.get(reservationId);
    }
}
