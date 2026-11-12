package com.iseonjwa.payment.reconciliation.application;

import com.iseonjwa.payment.reconciliation.domain.ChangeFeedProgress;
import com.iseonjwa.payment.reconciliation.domain.ReconciliationPolicy;
import com.iseonjwa.payment.reconciliation.domain.ReservationSnapshot;
import com.iseonjwa.payment.reconciliation.domain.ReservationView;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
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
 * <p>변경 목록은 매번 7일 전체를 읽지 않는다. 지난 회차에 다 읽은 구간의 끝(워터마크)에서 허용 지연 + 1분만큼 되돌린 시각부터
 * 읽고, 한 회차가 읽는 쪽 수는 예산으로 막는다. 예산을 다 쓰면 실패로 보지 않고 읽은 항목으로 판단하며, 남은 구간과 다음
 * 커서를 기억해 다음 회차에 이어 읽는다. 첫 회차와 재시작 뒤에는 7일치를 이렇게 몇 회차에 걸쳐 따라잡는다. 진행 상태는
 * {@link #commit}으로만 옮긴다.
 *
 * <p>Note: 왜 겹쳐 읽는가. 지난 회차에 허용 지연 안이라 판단을 미룬 예매와 커밋이 늦게 보인 예매를 이번 회차에 다시 판단해야
 * 해서다. 규칙은 같은 것을 두 번 판단해도 결과가 같으므로 겹침은 무해하다. 왜 판단 뒤에만 확정하는가. 규칙이 실패하거나 잠금
 * 때문에 건너뛴 회차에서 진행을 옮기면 그 구간의 예매가 영영 판단되지 않아서다. 옮기지 않으면 다음 회차가 같은 구간을 다시 읽는다.
 *
 * <p>Note: 변경 목록이 일부여도 오탐이 생기지 않는다. 규칙은 변경 목록을 대상을 더하는 데만 쓰고(RC-4의 출발점, 결제가 7일보다
 * 오래됐어도 최근 취소된 예매를 결제 쪽 대상에 보강), "변경 목록에 없음"으로 무엇을 판정하지 않는다. RC-2의 "예매 없음"은 묶음
 * 조회에서만 나온다. 못 읽은 부분은 다음 회차에 판단된다.
 *
 * <p>Note: 호출 수는 (대상 예매 수 - 변경 목록에 나온 수) / 묶음 크기 + 변경 목록 쪽 수(회차당 예산까지)다. 한 호출이 시한을
 * 넘기면 회차 전체의 예매 규칙이 실패한다. 부분 결과로 판단하면 못 받은 예매가 "예매 없음"(RC-2)으로 보일 수 있어서다. 같은
 * 이유로 200 응답이라도 대량으로 빠졌으면(묶음 하나가 통째로 비었거나 회차 전체 누락이 많으면) 예매 서비스 이상으로 보고 조회
 * 실패로 처리한다.
 */
public class ReservationViewLoader {
    private static final Logger log = LoggerFactory.getLogger(ReservationViewLoader.class);
    private static final Duration OVERLAP_MARGIN = Duration.ofMinutes(1);

    /**
     * @param batchSize        묶음 조회 한 번의 ID 수
     * @param missingBatchMin  묶음 하나에서 이 수 이상 물었는데 하나도 안 오면 실패
     * @param missingMinCount  회차 전체 누락이 이 수 이상이고 비율을 넘으면 실패
     * @param missingMaxRatio  물은 수 대비 누락 비율의 상한
     * @param changePageBudget 한 회차가 읽는 변경 목록 최대 쪽 수. 다 쓰면 남은 구간을 다음 회차에 이어 읽는다
     */
    public record Limits(int batchSize, int missingBatchMin, int missingMinCount, double missingMaxRatio,
            int changePageBudget) {}

    private final ReservationTargets targets;
    private final ReservationLookup lookup;
    private final Limits limits;
    private final ChangeFeedProgress progress = new ChangeFeedProgress();

    public ReservationViewLoader(ReservationTargets targets, ReservationLookup lookup, Limits limits) {
        this.targets = targets;
        this.lookup = lookup;
        this.limits = limits;
    }

    /** 그 회차의 규칙이 모두 판단을 마친 뒤에만 부른다. {@link ReservationView#next()}를 넘긴다. */
    public void commit(ChangeFeedProgress.State next) {
        progress.commit(next);
    }

    public ReservationView load(Instant now, ReconciliationPolicy policy) {
        var since = now.minus(policy.window());
        var byId = new HashMap<Long, ReservationSnapshot>();
        var state = progress.current();
        var segment = ChangeFeedProgress.plan(state, now, policy.window(), policy.allowedLag().plus(OVERLAP_MARGIN));
        var read = readChanges(segment);
        var changes = read.items();
        for (var change : changes) byId.put(change.reservationId(), change);
        log.info("event=RECONCILIATION_CHANGES from={} to={} pages={} items={} done={} budget={}", segment.from(),
                segment.to(), read.pages(), changes.size(), read.done(), limits.changePageBudget());
        var next = read.done()
                ? new ChangeFeedProgress.State(segment.to(), null)
                : new ChangeFeedProgress.State(state.watermark(),
                        new ChangeFeedProgress.Remaining(segment.from(), segment.to(), read.nextCursor()));

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
        return new ReservationView(requested, byId, changes, next);
    }

    private record ChangesRead(ArrayList<ReservationSnapshot> items, int pages, boolean done, String nextCursor) {}

    private ChangesRead readChanges(ChangeFeedProgress.Segment segment) {
        var changes = new ArrayList<ReservationSnapshot>();
        var seenCursors = new HashSet<String>();
        var cursor = segment.cursor();
        if (cursor != null) seenCursors.add(cursor);
        for (var pages = 1;; pages++) {
            var page = lookup.findChanges(segment.from(), segment.to(), cursor);
            changes.addAll(page.items());
            cursor = page.nextCursor();
            // 빈 쪽이면 더 읽지 않는다. 서버가 빈 쪽에 새 커서를 계속 주어도 끝난다
            if (cursor == null || page.items().isEmpty()) return new ChangesRead(changes, pages, true, null);
            if (!seenCursors.add(cursor)) throw new ReservationLookupException("findChanges 커서가 되풀이된다: " + cursor);
            // 예산을 다 써도 실패가 아니다. 읽은 항목으로 판단하고 남은 구간은 다음 회차에 이어 읽는다
            if (pages >= limits.changePageBudget()) return new ChangesRead(changes, pages, false, cursor);
        }
    }
}
