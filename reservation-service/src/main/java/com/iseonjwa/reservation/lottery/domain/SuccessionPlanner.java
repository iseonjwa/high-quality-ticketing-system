package com.iseonjwa.reservation.lottery.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 승계할 예비자를 고른다(D13). 남은 좌석 수만큼 예비자를 순위 순으로 당첨시키고, 매수가 남은 좌석보다 많은 예비자는
 * 건너뛴다. 건너뛴 예비자는 그대로 WAITLISTED로 남아 다음 승계 때 다시 기회를 얻는다(SeatAllocator.succeed와 같은 규칙).
 * 실제 좌석은 고르지 않는다. 좌석은 배정 워커가 예매를 만들 때 남은 추첨석에서 고른다(D10).
 */
public final class SuccessionPlanner {

    private SuccessionPlanner() {}

    /**
     * @param waitlistInRankOrder 아직 WAITLISTED인 응모를 순위 순으로
     * @param remainingSeats      추첨 좌석 수에서 당첨·결제 완료 매수를 뺀 값
     * @return 새로 당첨시킬 응모. 순위 순이다
     */
    public static List<DrawEntrant> select(List<DrawEntrant> waitlistInRankOrder, int remainingSeats) {
        var selected = new ArrayList<DrawEntrant>();
        int left = remainingSeats;
        for (var entrant : waitlistInRankOrder) {
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
