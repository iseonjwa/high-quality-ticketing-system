package com.iseonjwa.payment.reconciliation.application;

import com.iseonjwa.payment.reconciliation.domain.ReconciliationPolicy;
import com.iseonjwa.payment.reconciliation.domain.ReservationSnapshot;
import com.iseonjwa.payment.reconciliation.domain.ReservationView;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.TreeSet;

/**
 * 한 회차의 예매 스냅숏을 가져온다. 결제 쪽 대상 예매는 묶음 조회로 100건씩, RC-4용 변경 목록은 커서로 끝까지 읽는다. 회차
 * 문맥이 한 번만 부르므로 규칙마다 다시 부르지 않는다. 트랜잭션 밖에서 부른다.
 *
 * <p>Note: 호출 수는 대상 예매 수 / 묶음 크기 + 변경 목록 쪽 수다. 한 호출이 시한(2초)을 넘기면 회차 전체의 예매 규칙이
 * 실패한다. 부분 결과로 판단하면 못 받은 예매가 "예매 없음"(RC-2)으로 보일 수 있어서다.
 */
public class ReservationViewLoader {
    private final ReservationTargets targets;
    private final ReservationLookup lookup;
    private final int batchSize;

    public ReservationViewLoader(ReservationTargets targets, ReservationLookup lookup, int batchSize) {
        this.targets = targets;
        this.lookup = lookup;
        this.batchSize = batchSize;
    }

    public ReservationView load(Instant now, ReconciliationPolicy policy) {
        var since = now.minus(policy.window());
        var ids = new ArrayList<>(new TreeSet<>(targets.reservationIds(since)));
        var requested = new HashSet<>(ids);

        var byId = new HashMap<Long, ReservationSnapshot>();
        for (var from = 0; from < ids.size(); from += batchSize) {
            var batch = ids.subList(from, Math.min(from + batchSize, ids.size()));
            for (var snapshot : lookup.findByIds(batch)) {
                // 묻지 않은 예매가 섞여 와도 무시한다. 없는 ID는 응답에서 빠지는 것으로 "예매 없음"을 안다
                if (requested.contains(snapshot.reservationId())) byId.put(snapshot.reservationId(), snapshot);
            }
        }

        var changes = new ArrayList<ReservationSnapshot>();
        var seenCursors = new HashSet<String>();
        String cursor = null;
        do {
            var page = lookup.findChanges(since, now, cursor);
            changes.addAll(page.items());
            cursor = page.nextCursor();
            if (cursor != null && !seenCursors.add(cursor)) {
                throw new ReservationLookupException("findChanges 커서가 되풀이된다: " + cursor);
            }
        } while (cursor != null);
        return new ReservationView(requested, byId, changes);
    }
}
