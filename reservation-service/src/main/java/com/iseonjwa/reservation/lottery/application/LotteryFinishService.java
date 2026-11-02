package com.iseonjwa.reservation.lottery.application;

import com.iseonjwa.reservation.lottery.domain.LotteryEntryRepository;
import com.iseonjwa.reservation.lottery.domain.LotteryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 추첨 종결과 추첨석 반환(D9·D14, ISE-91). 당첨 예매가 모두 확정·실패하고 승계할 예비자도 없으면 추첨을 DONE으로 바꾸고,
 * 같은 트랜잭션에서 그 회차의 추첨석을 원래 등급으로 되돌린다. DONE이면 반환도 끝났다는 뜻이 된다.
 */
@Service
@RequiredArgsConstructor
public class LotteryFinishService {

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
        var lottery = lotteryRepository.lockForFinish(lotteryId);
        // Note: 잠금을 얻은 뒤 새 문장으로 다시 확인한다. READ COMMITTED에서는 문장마다 새 스냅샷이라,
        // 후보를 고른 뒤 잠금 전에 커밋된 승계(WAITLISTED → WON)도 여기서 보인다
        if (lottery.isEmpty() || lotteryEntryRepository.hasUnsettled(lotteryId)) {
            return Optional.empty();
        }
        lotteryRepository.markDone(lotteryId);
        return Optional.of(lotterySeatPort.revertAllFromLottery(lottery.get().getScheduleId(),
                lottery.get().getSourceGrade(), null));
    }

    public long countRemainingLotterySeats() {
        return lotterySeatPort.countAllLotterySeats();
    }
}
