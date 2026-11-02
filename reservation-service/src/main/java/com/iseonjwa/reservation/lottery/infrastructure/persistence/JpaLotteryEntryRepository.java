package com.iseonjwa.reservation.lottery.infrastructure.persistence;

import com.iseonjwa.reservation.lottery.domain.DrawEntrant;
import com.iseonjwa.reservation.lottery.domain.LotteryEntry;
import com.iseonjwa.reservation.lottery.domain.LotteryEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JpaLotteryEntryRepository implements LotteryEntryRepository {

    private final SpringDataLotteryEntryRepository springDataLotteryEntryRepository;
    private final JdbcTemplate jdbcTemplate;

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

    // Note: 당첨·예비 수천 건을 JDBC 배치로 보낸다. JPA 트랜잭션이 연 같은 커넥션을 쓰므로 추첨 트랜잭션에 함께 묶인다.
    // 조건 status = 'ENTERED'는 추첨 중 응모가 바뀌지 않았는지 지키는 마지막 방어선이다(추첨은 마감 뒤라 바뀌지 않아야 한다)
    @Override
    public void saveDrawResults(List<DrawResult> results) {
        jdbcTemplate.batchUpdate("UPDATE lottery_entries SET status = ?, draw_rank = ?, updated_at = now()"
                        + " WHERE id = ? AND status = 'ENTERED'",
                results, 500, (statement, result) -> {
                    statement.setString(1, result.status().name());
                    statement.setInt(2, result.drawRank());
                    statement.setLong(3, result.entryId());
                });
    }

    @Override
    public int markRemainingLost(Long lotteryId) {
        return springDataLotteryEntryRepository.markRemainingLost(lotteryId);
    }
}
