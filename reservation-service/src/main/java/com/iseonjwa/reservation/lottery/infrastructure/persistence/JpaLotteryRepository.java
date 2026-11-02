package com.iseonjwa.reservation.lottery.infrastructure.persistence;

import com.iseonjwa.reservation.lottery.domain.Lottery;
import com.iseonjwa.reservation.lottery.domain.LotteryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaLotteryRepository implements LotteryRepository {

    private final SpringDataLotteryRepository springDataLotteryRepository;

    @Override
    public Lottery save(Lottery lottery) {
        return springDataLotteryRepository.save(lottery);
    }

    @Override
    public boolean existsByScheduleId(Long scheduleId) {
        return springDataLotteryRepository.existsByScheduleId(scheduleId);
    }

    @Override
    public Optional<Lottery> findById(Long id) {
        return springDataLotteryRepository.findById(id);
    }

    @Override
    public Optional<Long> findScheduleIdById(Long id) {
        return springDataLotteryRepository.findScheduleIdById(id);
    }

    @Override
    public int closeDueLotteries() {
        return springDataLotteryRepository.closeDueLotteries();
    }

    @Override
    public List<Long> findDrawDueIds() {
        return springDataLotteryRepository.findDrawDueIds();
    }

    @Override
    public List<Long> findSuccessionOpenIds() {
        return springDataLotteryRepository.findSuccessionOpenIds();
    }

    @Override
    public Optional<Integer> lockForSuccession(Long id) {
        return springDataLotteryRepository.lockForSuccession(id).stream().findFirst();
    }

    @Override
    public boolean markDrawn(Long id) {
        springDataLotteryRepository.setLocalLockTimeout();
        return springDataLotteryRepository.markDrawn(id) == 1;
    }
}
