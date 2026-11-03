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
 * 한 회차의 예매 스냅숏을 가져온다. 먼저 변경 목록(기간 안에 {@code CONFIRMED}·{@code CANCELED}로 바뀐 예매)을 커서로 읽고,
 * 결제 쪽 대상 예매 중 변경 목록에 이미 나온 것을 뺀 나머지를 묶음 조회로 100건씩 묻는다. 변경 목록의 항목은 현재 상태라 묶음
 * 조회 결과와 같은 저장소에 합친다. 회차 문맥이 한 번만 부르므로 규칙마다 다시 부르지 않는다. 트랜잭션 밖에서 부른다.
 *
 * <p>Note: 변경 목록은 RC-4의 출발점이자, 결제가 7일보다 오래됐어도 예매가 최근에 취소된 경우(RC-2 등)를 결제 쪽 대상에
 * 더하는 데 쓴다. 변경 목록이 대상에서 빠지면 오래된 승인 결제의 뒤늦은 예매 취소를 찾지 못한다.
 *
 * <p>Note: 호출 수는 (대상 예매 수 - 변경 목록에 나온 수) / 묶음 크기 + 변경 목록 쪽 수다. 한 호출이 시한을 넘기면 회차 전체의
 * 예매 규칙이 실패한다. 부분 결과로 판단하면 못 받은 예매가 "예매 없음"(RC-2)으로 보일 수 있어서다. 같은 이유로 200 응답이라도
 * 대량으로 빠졌으면(묶음 하나가 통째로 비었거나 회차 전체 누락이 많으면) 예매 서비스 이상으로 보고 조회 실패로 처리한다.
 */
public class ReservationViewLoader {
    /**
     * @param batchSize        묶음 조회 한 번의 ID 수
     * @param missingBatchMin  묶음 하나에서 이 수 이상 물었는데 하나도 안 오면 실패
     * @param missingMinCount  회차 전체 누락이 이 수 이상이고 비율을 넘으면 실패
     * @param missingMaxRatio  물은 수 대비 누락 비율의 상한
     * @param maxChangePages   변경 목록 최대 쪽 수
     */
    public record Limits(int batchSize, int missingBatchMin, int missingMinCount, double missingMaxRatio,
            int maxChangePages) {}

    private final ReservationTargets targets;
    private final ReservationLookup lookup;
    private final Limits limits;

    public ReservationViewLoader(ReservationTargets targets, ReservationLookup lookup, Limits limits) {
        this.targets = targets;
        this.lookup = lookup;
        this.limits = limits;
    }

    public ReservationView load(Instant now, ReconciliationPolicy policy) {
        var since = now.minus(policy.window());
        var byId = new HashMap<Long, ReservationSnapshot>();
        var changes = readChanges(since, now);
        for (var change : changes) byId.put(change.reservationId(), change);

        var toAsk = new ArrayList<>(new TreeSet<>(targets.reservationIds(since)));
        toAsk.removeIf(byId::containsKey);
        var requested = new HashSet<>(byId.keySet());
        requested.addAll(toAsk);

        var missing = 0;
        for (var from = 0; from < toAsk.size(); from += limits.batchSize()) {
            var batch = toAsk.subList(from, Math.min(from + limits.batchSize(), toAsk.size()));
            var asked = new HashSet<>(batch);
            var received = 0;
            for (var snapshot : lookup.findByIds(batch)) {
                // 묻지 않은 예매가 섞여 와도 무시한다. 없는 ID는 응답에서 빠지는 것으로 "예매 없음"을 안다
                if (asked.contains(snapshot.reservationId()) && byId.put(snapshot.reservationId(), snapshot) == null) {
                    received++;
                }
            }
            if (batch.size() >= limits.missingBatchMin() && received == 0) {
                throw new ReservationLookupException("묶음 조회 " + batch.size() + "건에 응답이 하나도 없다(예매 서비스 이상으로 본다)");
            }
            missing += batch.size() - received;
        }
        if (missing >= limits.missingMinCount() && missing > limits.missingMaxRatio() * toAsk.size()) {
            throw new ReservationLookupException("묶음 조회 " + toAsk.size() + "건 중 " + missing
                    + "건이 예매 없음으로 왔다(예매 서비스 이상으로 본다)");
        }
        return new ReservationView(requested, byId, changes);
    }

    private ArrayList<ReservationSnapshot> readChanges(Instant since, Instant now) {
        var changes = new ArrayList<ReservationSnapshot>();
        var seenCursors = new HashSet<String>();
        String cursor = null;
        for (var pages = 1;; pages++) {
            if (pages > limits.maxChangePages()) {
                throw new ReservationLookupException("변경 목록이 " + limits.maxChangePages() + "쪽을 넘었다");
            }
            var page = lookup.findChanges(since, now, cursor);
            changes.addAll(page.items());
            cursor = page.nextCursor();
            // 빈 쪽이면 더 읽지 않는다. 서버가 빈 쪽에 새 커서를 계속 주어도 끝난다
            if (cursor == null || page.items().isEmpty()) return changes;
            if (!seenCursors.add(cursor)) throw new ReservationLookupException("findChanges 커서가 되풀이된다: " + cursor);
        }
    }
}
