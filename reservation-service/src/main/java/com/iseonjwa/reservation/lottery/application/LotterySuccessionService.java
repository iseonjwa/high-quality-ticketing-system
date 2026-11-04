package com.iseonjwa.reservation.lottery.application;

import com.iseonjwa.reservation.lottery.domain.DrawEntrant;
import com.iseonjwa.reservation.lottery.domain.LotteryEntryRepository;
import com.iseonjwa.reservation.lottery.domain.LotteryRepository;
import com.iseonjwa.reservation.lottery.domain.SuccessionPlanner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 예비자 승계와 승계 마감(D13·D14, ISE-93). 실패 경로(결제 거절, 선점 만료, 배정 거절)는 응모를 FAILED로만 바꾸고,
 * 빈 자리를 예비자로 채우는 일은 여기 한 곳이 추첨 행 잠금으로 줄 세워 처리한다. 예매는 배정 워커(ISE-90)가 만든다.
 */
@Service
@RequiredArgsConstructor
public class LotterySuccessionService {

    private final LotteryRepository lotteryRepository;
    private final LotteryEntryRepository lotteryEntryRepository;

    public List<Long> findSuccessionOpenIds() {
        return lotteryRepository.findSuccessionOpenIds();
    }

    /**
     * 한 추첨의 빈 자리를 예비자로 채운다.
     *
     * @return 새로 당첨시킨 수. 다른 워커가 잡고 있거나 승계 마감이 지났으면 0
     */
    @Transactional
    public int succeed(Long lotteryId) {
        var seatCount = lotteryRepository.lockForSuccession(lotteryId);
        if (seatCount.isEmpty()) {
            return 0;
        }
        int remaining = seatCount.get() - lotteryEntryRepository.sumTakenSeats(lotteryId);
        if (remaining <= 0) {
            return 0;
        }
        var selected = SuccessionPlanner.select(lotteryEntryRepository.findWaitlist(lotteryId), remaining);
        return lotteryEntryRepository.promoteToWon(selected.stream().map(DrawEntrant::entryId).toList());
    }

    @Transactional
    public int closeExpiredWaitlists() {
        return lotteryEntryRepository.closeExpiredWaitlists();
    }
}
