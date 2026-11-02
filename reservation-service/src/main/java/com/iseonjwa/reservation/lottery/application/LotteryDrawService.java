package com.iseonjwa.reservation.lottery.application;

import com.iseonjwa.reservation.lottery.domain.DrawEntrant;
import com.iseonjwa.reservation.lottery.domain.DrawRanker;
import com.iseonjwa.reservation.lottery.domain.LotteryEntryRepository;
import com.iseonjwa.reservation.lottery.domain.LotteryEntryRepository.DrawResult;
import com.iseonjwa.reservation.lottery.domain.LotteryEntryStatus;
import com.iseonjwa.reservation.lottery.domain.LotteryRepository;
import com.iseonjwa.reservation.lottery.domain.SeatAllocator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 응모 마감과 추첨 실행(D8, details.md 4절). 추첨 하나를 한 트랜잭션으로 처리한다.
 * 순위와 배정은 메모리에서 계산하고, DB에는 당첨·예비만 배치로 쓰고 낙첨은 한 문장으로 바꾼다.
 */
@Service
@RequiredArgsConstructor
public class LotteryDrawService {

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
     * 추첨을 한 번만 실행한다. 이미 추첨했거나 시각 전이면 아무것도 하지 않는다.
     *
     * @return 이번 호출이 추첨을 실행했으면 true
     */
    // Note: 시간 상한 10초(numbers-and-measurement.md). 넘으면 트랜잭션이 롤백되고 상태가 CLOSED로 남아
    // 다음 주기에 다시 시도한다. 연속으로 넘으면 청크 방식(D8 B안)으로 바꾸는 근거로 본다.
    @Transactional(timeout = 10)
    public boolean draw(Long lotteryId) {
        if (!lotteryRepository.markDrawn(lotteryId)) {
            return false;
        }
        var lottery = lotteryRepository.findById(lotteryId).orElseThrow();
        var ranked = DrawRanker.rank(lottery.getSeed(), lotteryEntryRepository.findDrawEntrants(lotteryId));
        var allocation = SeatAllocator.draw(ranked,
                lotterySeatPort.findLotterySeats(lottery.getScheduleId()), lottery.getWaitlistCount());

        var rankOf = new HashMap<Long, Integer>();
        for (int i = 0; i < ranked.size(); i++) {
            rankOf.put(ranked.get(i).entryId(), i + 1);
        }
        var results = new ArrayList<DrawResult>();
        for (var winner : allocation.winners()) {
            results.add(result(winner.entrant(), LotteryEntryStatus.WON, rankOf));
        }
        for (var waitlisted : allocation.waitlisted()) {
            results.add(result(waitlisted, LotteryEntryStatus.WAITLISTED, rankOf));
        }
        lotteryEntryRepository.saveDrawResults(results);
        lotteryEntryRepository.markRemainingLost(lotteryId);
        return true;
    }

    private static DrawResult result(DrawEntrant entrant, LotteryEntryStatus status, HashMap<Long, Integer> rankOf) {
        return new DrawResult(entrant.entryId(), status, rankOf.get(entrant.entryId()));
    }
}
