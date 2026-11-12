package com.iseonjwa.payment.reconciliation.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * 예매 변경 목록을 어디까지 읽었는지의 진행 상태. 메모리에만 두므로 재시작하면 처음(7일 전)부터 다시 따라잡는다. 인스턴스마다
 * 따로 갖는다. 대사는 advisory 잠금으로 한 번에 하나만 돌고, 인스턴스가 바뀌어 겹쳐 읽어도 규칙의 판단이 멱등이라 무해하다.
 *
 * <p>상태는 두 가지다. 워터마크는 "이 시각 전까지는 다 읽고 판단했다"이고, 남은 구간은 회차 예산이 모자라 덜 읽은 구간과 그
 * 다음 커서다. 상태는 {@link #commit}으로만 앞으로 옮긴다. 그 회차의 규칙이 모두 판단을 마친 뒤에만 부른다.
 */
public final class ChangeFeedProgress {
    /** 덜 읽은 구간. 키셋 커서는 같은 {@code [from, to)} 안에서만 이어지므로 구간을 함께 기억한다. */
    public record Remaining(Instant from, Instant to, String cursor) {}

    /** @param watermark 다 읽은 구간의 끝. 첫 회차는 null @param remaining 덜 읽은 구간. 없으면 null */
    public record State(Instant watermark, Remaining remaining) {
        public static final State EMPTY = new State(null, null);
    }

    /** 이번 회차가 읽을 구간. {@code cursor}는 이어 읽을 때만 있다. */
    public record Segment(Instant from, Instant to, String cursor) {}

    private State state = State.EMPTY;

    public synchronized State current() {
        return state;
    }

    public synchronized void commit(State next) {
        this.state = next;
    }

    /**
     * 이번 회차의 읽을 구간을 정한다.
     *
     * @param window  정책의 조회 기간(7일)
     * @param overlap 워터마크에서 되돌려 겹쳐 읽는 길이
     */
    public static Segment plan(State state, Instant now, Duration window, Duration overlap) {
        var floor = now.minus(window);
        var remaining = state.remaining();
        if (remaining != null && remaining.to().isAfter(floor)) {
            return new Segment(remaining.from(), remaining.to(), remaining.cursor());
        }
        if (state.watermark() == null) return new Segment(floor, now, null);
        var from = state.watermark().minus(overlap);
        return new Segment(from.isAfter(floor) ? from : floor, now, null);
    }
}
