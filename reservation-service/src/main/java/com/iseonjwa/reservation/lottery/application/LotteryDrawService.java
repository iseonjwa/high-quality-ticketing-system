package com.iseonjwa.reservation.lottery.application;

import com.iseonjwa.reservation.lottery.domain.entity.Lottery;
import com.iseonjwa.reservation.lottery.domain.entity.LotteryEntryStatus;
import com.iseonjwa.reservation.lottery.domain.policy.DrawEntrant;
import com.iseonjwa.reservation.lottery.domain.policy.DrawRanker;
import com.iseonjwa.reservation.lottery.domain.policy.SeatAllocator;
import com.iseonjwa.reservation.lottery.domain.policy.SeatAllocator.DrawAllocation;
import com.iseonjwa.reservation.lottery.domain.policy.SeatAllocator.SeatAssignment;
import com.iseonjwa.reservation.lottery.domain.repository.LotteryEntryRepository;
import com.iseonjwa.reservation.lottery.domain.repository.LotteryEntryRepository.DrawResult;
import com.iseonjwa.reservation.lottery.domain.repository.LotteryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 추첨 하나를 한 트랜잭션에서 메모리로 계산한 뒤 당첨·예비는 배치로, 낙첨은 한 문장으로 쓴다(D8, details.md 4절).
 */
@Service
@RequiredArgsConstructor
public class LotteryDrawService {

    private static final int DRAW_TIMEOUT_SECONDS = 10;

    private final LotteryRepository lotteryRepository;
    private final LotteryEntryRepository lotteryEntryRepository;
    private final LotterySeatPort lotterySeatPort;

    @Transactional
    public int closeDueLotteries() {
        return lotteryRepository.closeDueLotteries();
    }

    public List<Long> findDrawDueIds() {
        return lotteryRepository.findDrawDueIds();
    }

    /**
     * 추첨을 한 번만 실행하며, 이미 추첨했거나 시각 전이면 아무것도 하지 않는다.
     *
     * @return 이번 호출이 추첨을 실행했으면 true
     */
    // Note: 상한(numbers-and-measurement.md)을 넘으면 롤백되어 CLOSED로 남아 다음 주기에 다시 시도하며, 연속으로 넘으면 청크 방식(D8 B안)으로 바꾸는 근거로 본다.
    @Transactional(timeout = DRAW_TIMEOUT_SECONDS)
    public boolean draw(Long lotteryId) {
        if (!lotteryRepository.markDrawn(lotteryId)) {
            return false;
        }

        Lottery lottery = lotteryRepository.findById(lotteryId).orElseThrow();
        List<DrawEntrant> ranked = DrawRanker.rank(
                lottery.getSeed(),
                lotteryEntryRepository.findDrawEntrants(lotteryId));
        DrawAllocation allocation = SeatAllocator.draw(
                ranked,
                lotterySeatPort.findLotterySeats(lottery.getScheduleId()),
                lottery.getWaitlistCount());

        lotteryEntryRepository.saveDrawResults(toDrawResults(ranked, allocation));
        lotteryEntryRepository.markRemainingLost(lotteryId);

        return true;
    }

    private static List<DrawResult> toDrawResults(List<DrawEntrant> ranked, DrawAllocation allocation) {
        Map<Long, Integer> rankByEntryId = new HashMap<>();
        for (int i = 0; i < ranked.size(); i++) {
            rankByEntryId.put(ranked.get(i).entryId(), i + 1);
        }

        List<DrawResult> results = new ArrayList<>();
        for (SeatAssignment winner : allocation.winners()) {
            results.add(toDrawResult(winner.entrant(), LotteryEntryStatus.WON, rankByEntryId));
        }
        for (DrawEntrant waitlisted : allocation.waitlisted()) {
            results.add(toDrawResult(waitlisted, LotteryEntryStatus.WAITLISTED, rankByEntryId));
        }

        return results;
    }

    private static DrawResult toDrawResult(DrawEntrant entrant,
                                           LotteryEntryStatus status,
                                           Map<Long, Integer> rankByEntryId) {
        return new DrawResult(entrant.entryId(), status, rankByEntryId.get(entrant.entryId()));
    }
}
