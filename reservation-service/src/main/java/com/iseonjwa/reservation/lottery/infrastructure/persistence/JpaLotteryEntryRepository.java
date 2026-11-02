package com.iseonjwa.reservation.lottery.infrastructure.persistence;

import com.iseonjwa.reservation.lottery.domain.DrawEntrant;
import com.iseonjwa.reservation.lottery.domain.LotteryEntry;
import com.iseonjwa.reservation.lottery.domain.LotteryEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JpaLotteryEntryRepository implements LotteryEntryRepository {

    private final SpringDataLotteryEntryRepository springDataLotteryEntryRepository;

    @Override
    public Optional<Long> insertIfOpen(Long lotteryId, UUID requestId, Long userId, int quantity) {
        return springDataLotteryEntryRepository.insertIfOpen(lotteryId, requestId, userId, quantity)
                .stream().findFirst();
    }

    @Override
    public boolean cancelIfOpen(Long lotteryId, Long userId) {
        return springDataLotteryEntryRepository.cancelIfOpen(lotteryId, userId) == 1;
    }

    @Override
    public Optional<LotteryEntry> findById(Long id) {
        return springDataLotteryEntryRepository.findById(id);
    }

    @Override
    public Optional<LotteryEntry> findByRequestId(UUID requestId) {
        return springDataLotteryEntryRepository.findByRequestId(requestId);
    }

    @Override
    public Optional<LotteryEntry> findByLotteryIdAndUserId(Long lotteryId, Long userId) {
        return springDataLotteryEntryRepository.findByLotteryIdAndUserId(lotteryId, userId);
    }

    @Override
    public List<DrawEntrant> findDrawEntrants(Long lotteryId) {
        return springDataLotteryEntryRepository.findDrawEntrantRows(lotteryId).stream()
                .map(row -> new DrawEntrant((Long) row[0], ((Number) row[1]).intValue()))
                .toList();
    }
}
