package com.iseonjwa.payment.reconciliation.application;

import com.iseonjwa.payment.reconciliation.domain.ReservationSnapshot;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * 예매 서비스의 읽기 전용 대사 조회(ADR 0029 결정 2). 호출 실패, 200이 아닌 응답(404 포함), 시한 초과, 알 수 없는 상태 값은
 * 모두 {@link ReservationLookupException}이다. 트랜잭션 밖에서 부른다.
 */
public interface ReservationLookup {
    /**
     * 예매 ID로 묶음 조회한다. 예매 서비스에 없는 ID는 결과에서 빠진다(= 예매 없음).
     * @param ids 최대 100건
     */
    List<ReservationSnapshot> findByIds(Collection<Long> ids);

    /**
     * {@code [from, to)} 안에 {@code CONFIRMED}·{@code CANCELED}로 바뀐 예매 한 쪽.
     * @param cursor 첫 쪽은 null
     */
    ChangesPage findChanges(Instant from, Instant to, String cursor);

    /** @param nextCursor 마지막 쪽이면 null */
    record ChangesPage(List<ReservationSnapshot> items, String nextCursor) {}
}
