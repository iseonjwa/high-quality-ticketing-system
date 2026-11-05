package com.iseonjwa.reservation.lottery.domain.policy;

import java.util.ArrayList;
import java.util.List;

/**
 * 남은 좌석 수만큼 예비자를 순위 순으로 고르고 매수가 더 많은 예비자는 다음 승계로 넘기며, 실제 좌석은 배정 워커가 고른다(D10, D13).
 */
public final class SuccessionPlanner {

    private SuccessionPlanner() {}

    /**
     * @param waitlistInRankOrder 아직 WAITLISTED인 응모를 순위 순으로
     * @param remainingSeats      추첨 좌석 수에서 당첨·결제 완료 매수를 뺀 값
     * @return 새로 당첨시킬 응모(순위 순)
     */
    public static List<DrawEntrant> select(List<DrawEntrant> waitlistInRankOrder, int remainingSeats) {
        List<DrawEntrant> selected = new ArrayList<>();
        int left = remainingSeats;

        for (DrawEntrant entrant : waitlistInRankOrder) {
            if (left <= 0) {
                break;
            }
            if (entrant.quantity() <= left) {
                selected.add(entrant);
                left -= entrant.quantity();
            }
        }

        return selected;
    }
}
