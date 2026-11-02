package com.iseonjwa.reservation.lottery.infrastructure.persistence;

import com.iseonjwa.reservation.lottery.domain.LotteryEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataLotteryEntryRepository extends JpaRepository<LotteryEntry, Long> {

    Optional<LotteryEntry> findByRequestId(UUID requestId);

    Optional<LotteryEntry> findByLotteryIdAndUserId(Long lotteryId, Long userId);

    // Note: 기간·매수 확인과 INSERT를 한 문장으로 해서, 마감 판정이 서버 시계가 아니라 DB 시각 하나로 정해진다.
    // ON CONFLICT DO NOTHING은 유니크 위반을 예외 대신 0행으로 돌려준다. PostgreSQL은 제약 위반 뒤
    // 트랜잭션을 중단시켜 이유를 다시 읽을 수 없으므로, 0행으로 받고 같은 트랜잭션에서 이유를 가린다.
    // 같은 사용자의 동시 응모는 유니크 인덱스에서 뒤 요청이 앞 요청의 커밋을 기다린 뒤 0행이 된다.
    @Query(nativeQuery = true, value = """
            INSERT INTO lottery_entries (lottery_id, request_id, user_id, quantity, status, payment_attempts,
                                         created_at, created_by, updated_at, updated_by)
            SELECT l.id, :requestId, :userId, :quantity, 'ENTERED', 0, now(), :userId, now(), :userId
            FROM lotteries l
            WHERE l.id = :lotteryId AND l.deleted_at IS NULL AND l.status = 'OPEN'
              AND now() >= l.entry_start_at AND now() < l.entry_end_at
              AND :quantity <= l.max_per_entry
            ON CONFLICT DO NOTHING
            RETURNING id
            """)
    List<Long> insertIfOpen(@Param("lotteryId") Long lotteryId, @Param("requestId") UUID requestId,
                            @Param("userId") Long userId, @Param("quantity") int quantity);

    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE lottery_entries e
            SET status = 'CANCELED', updated_at = now(), updated_by = :userId
            FROM lotteries l
            WHERE e.lottery_id = l.id AND e.lottery_id = :lotteryId AND e.user_id = :userId
              AND e.status = 'ENTERED'
              AND l.status = 'OPEN' AND now() < l.entry_end_at
            """)
    int cancelIfOpen(@Param("lotteryId") Long lotteryId, @Param("userId") Long userId);

    @Query("select e.id, e.quantity from LotteryEntry e where e.lotteryId = :lotteryId and e.status = 'ENTERED'"
            + " order by e.id")
    List<Object[]> findDrawEntrantRows(@Param("lotteryId") Long lotteryId);

    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE lottery_entries SET status = 'LOST', updated_at = now()
            WHERE lottery_id = :lotteryId AND status = 'ENTERED'
            """)
    int markRemainingLost(@Param("lotteryId") Long lotteryId);
}
