package com.iseonjwa.reservation.lottery.application;

import com.iseonjwa.reservation.lottery.domain.DrawEntrant;
import com.iseonjwa.reservation.lottery.domain.LotteryEntryRepository;
import com.iseonjwa.reservation.lottery.domain.LotteryRepository;
import com.iseonjwa.reservation.lottery.domain.SuccessionPlanner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 빈 자리를 예비자로 채우는 일을 추첨 행 잠금으로 줄 세워 여기 한 곳에서 처리하며, 예매는 배정 워커가 만든다(D13·D14, ISE-93).
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
        Optional<Integer> seatCount = lotteryRepository.lockForSuccession(lotteryId);
        if (seatCount.isEmpty()) {
            return 0;
        }

        int remaining = seatCount.get() - lotteryEntryRepository.sumTakenSeats(lotteryId);
        if (remaining <= 0) {
            return 0;
        }

        List<DrawEntrant> selected = SuccessionPlanner.select(lotteryEntryRepository.findWaitlist(lotteryId), remaining);
        List<Long> selectedEntryIds = selected.stream()
                .map(DrawEntrant::entryId)
                .toList();

        return lotteryEntryRepository.promoteToWon(selectedEntryIds);
    }

    @Transactional
    public int closeExpiredWaitlists() {
        return lotteryEntryRepository.closeExpiredWaitlists();
    }
}
