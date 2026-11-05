package com.iseonjwa.reservation.lottery.infrastructure.persistence;

import com.iseonjwa.reservation.lottery.domain.LotteryEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataLotteryEntryRepository extends JpaRepository<LotteryEntry, Long> {

    Optional<LotteryEntry> findByRequestId(UUID requestId);

    Optional<LotteryEntry> findByLotteryIdAndUserId(Long lotteryId, Long userId);

    // Note: 마감을 DB 시각 하나로 판정하려고 조건 확인과 INSERT를 한 문장으로 하고, 제약 위반으로 트랜잭션이 중단되지 않게
    // ON CONFLICT DO NOTHING으로 0행을 받아 이유를 가리며, 같은 사용자의 동시 응모는 앞 요청의 커밋을 기다린 뒤 0행이 된다.
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

    // Note: FOR UPDATE OF e SKIP LOCKED로 여러 워커가 같은 당첨자를 두 번 배정하지 않고, 잡힌 행은 건너뛰어 한 명이 늦어도 나머지 배정이 막히지 않는다.
    @Query(nativeQuery = true, value = """
            SELECT e.id, e.lottery_id, l.schedule_id, e.user_id, e.quantity
            FROM lottery_entries e JOIN lotteries l ON l.id = e.lottery_id
            WHERE e.status = 'WON' AND e.reservation_id IS NULL AND l.status = 'DRAWN'
              AND e.id NOT IN (:skipIds)
            ORDER BY e.draw_rank, e.id
            LIMIT 1
            FOR UPDATE OF e SKIP LOCKED
            """)
    List<Object[]> lockNextUnassignedWinner(@Param("skipIds") Collection<Long> skipIds);

    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE lottery_entries
            SET reservation_id = :reservationId, payment_attempts = payment_attempts + 1, updated_at = now()
            WHERE id = :entryId AND status = 'WON' AND reservation_id IS NULL
            """)
    int linkReservation(@Param("entryId") Long entryId, @Param("reservationId") Long reservationId);

    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE lottery_entries SET status = 'FAILED', fail_reason = :reason, updated_at = now()
            WHERE id = :entryId AND status = 'WON' AND reservation_id IS NULL
            """)
    int markAssignmentFailed(@Param("entryId") Long entryId, @Param("reason") String reason);

    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE lottery_entries e SET status = 'FAILED', fail_reason = 'NO_SEAT', updated_at = now()
            FROM lotteries l
            WHERE e.lottery_id = l.id AND e.id = :entryId AND e.status = 'WON' AND e.reservation_id IS NULL
              AND l.draw_at + interval '30 minutes' <= now()
            """)
    int markNoSeatFailedAfterSuccessionDeadline(@Param("entryId") Long entryId);

    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE lottery_entries SET status = 'PAID', updated_at = now()
            WHERE reservation_id = :reservationId AND status = 'WON'
            """)
    int markPaid(@Param("reservationId") Long reservationId);

    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE lottery_entries SET status = 'FAILED', fail_reason = :reason, updated_at = now()
            WHERE reservation_id = :reservationId AND status = 'WON'
            """)
    int markFailed(@Param("reservationId") Long reservationId, @Param("reason") String reason);

    // 승계 마감(추첨 후 30분, D13) 전이고 시도 횟수가 남았을 때만 연결을 비운다. 시각은 DB 시각이다
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE lottery_entries e SET reservation_id = NULL, updated_at = now()
            FROM lotteries l
            WHERE e.lottery_id = l.id AND e.reservation_id = :reservationId AND e.status = 'WON'
              AND e.payment_attempts < :maxAttempts AND now() < l.draw_at + interval '30 minutes'
            """)
    int releaseForRetry(@Param("reservationId") Long reservationId, @Param("maxAttempts") int maxAttempts);

    @Query(nativeQuery = true, value = """
            SELECT coalesce(sum(quantity), 0) FROM lottery_entries
            WHERE lottery_id = :lotteryId AND status IN ('WON', 'PAID')
            """)
    int sumTakenSeats(@Param("lotteryId") Long lotteryId);

    @Query("select e.id, e.quantity from LotteryEntry e where e.lotteryId = :lotteryId and e.status = 'WAITLISTED'"
            + " order by e.drawRank, e.id")
    List<Object[]> findWaitlistRows(@Param("lotteryId") Long lotteryId);

    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE lottery_entries SET status = 'WON', updated_at = now()
            WHERE id IN (:entryIds) AND status = 'WAITLISTED'
            """)
    int promoteToWon(@Param("entryIds") List<Long> entryIds);

    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE lottery_entries e SET status = 'LOST', updated_at = now()
            FROM lotteries l
            WHERE e.lottery_id = l.id AND l.status = 'DRAWN' AND l.draw_at + interval '30 minutes' <= now()
              AND e.status = 'WAITLISTED'
            """)
    int closeExpiredWaitlists();

    @Query(nativeQuery = true, value = """
            SELECT EXISTS (SELECT 1 FROM lottery_entries
                           WHERE lottery_id = :lotteryId AND status IN ('WON', 'WAITLISTED'))
            """)
    boolean hasUnsettled(@Param("lotteryId") Long lotteryId);

    @Query(nativeQuery = true, value = """
            SELECT l.draw_at FROM lottery_entries e JOIN lotteries l ON l.id = e.lottery_id
            WHERE e.reservation_id = :reservationId AND e.status = 'WON'
            """)
    List<Instant> findWonDrawAtByReservationId(@Param("reservationId") Long reservationId);
}
