package com.iseonjwa.reservation.lottery.application;

import com.iseonjwa.reservation.lottery.domain.entity.Lottery;
import com.iseonjwa.reservation.lottery.domain.repository.LotteryEntryRepository;
import com.iseonjwa.reservation.lottery.domain.repository.LotteryRepository;
import com.iseonjwa.reservation.lottery.observability.LotteryMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 끝나지 않은 응모가 없으면 추첨을 DONE으로 바꾸고 같은 트랜잭션에서 추첨석을 원래 등급으로 되돌린다(D9·D14, ISE-91).
 */
@Service
@RequiredArgsConstructor
public class LotteryFinishService {

    // 스케줄러가 종결하므로 좌석 변경의 행위자가 없다
    private static final Long SYSTEM_ACTOR_ID = null;

    private final LotteryRepository lotteryRepository;
    private final LotteryEntryRepository lotteryEntryRepository;
    private final LotterySeatPort lotterySeatPort;

    public List<Long> findFinishDueIds() {
        return lotteryRepository.findFinishDueIds();
    }

    /**
     * 한 추첨을 종결한다.
     *
     * @return 되돌린 추첨석 수. 다른 워커가 잡고 있거나, 이미 종결했거나, 끝나지 않은 응모가 남아 있으면 비어 있다
     */
    @Transactional
    public Optional<Integer> finish(Long lotteryId) {
        Optional<Lottery> lockedLottery = lotteryRepository.lockForFinish(lotteryId);

        // Note: READ COMMITTED는 문장마다 새 스냅샷이라, 후보 선정 뒤 잠금 전에 커밋된 승계도 잠근 뒤의 새 문장으로 확인한다.
        if (lockedLottery.isEmpty() || lotteryEntryRepository.hasUnsettled(lotteryId)) {
            return Optional.empty();
        }

        Lottery lottery = lockedLottery.get();
        lotteryRepository.markDone(lotteryId);

        return Optional.of(lotterySeatPort.revertAllFromLottery(
                lottery.getScheduleId(),
                lottery.getSourceGrade(),
                SYSTEM_ACTOR_ID));
    }

    public LotteryMetrics.Progress readProgress() {
        return new LotteryMetrics.Progress(
                lotteryRepository.countUnfinished(),
                lotteryRepository.findOldestUnfinishedSeconds(),
                lotteryEntryRepository.countUnsettledWinners(),
                lotterySeatPort.countAllLotterySeats());
    }
}
