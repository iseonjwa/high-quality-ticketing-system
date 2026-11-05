package com.iseonjwa.reservation.lottery.infrastructure.persistence;

import com.iseonjwa.reservation.lottery.domain.entity.Lottery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

interface SpringDataLotteryRepository extends JpaRepository<Lottery, Long> {

    boolean existsByScheduleId(Long scheduleId);

    // 회차 잠금 뒤 findById가 다른 수정이 커밋한 최신 행을 읽도록 엔티티 대신 값만 읽어 영속성 컨텍스트에 올리지 않는다
    @Query("select l.scheduleId from Lottery l where l.id = :id")
    Optional<Long> findScheduleIdById(@Param("id") Long id);

    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE lotteries SET status = 'CLOSED', updated_at = now()
            WHERE status = 'OPEN' AND entry_end_at <= now() AND deleted_at IS NULL
            """)
    int closeDueLotteries();

    @Query(nativeQuery = true, value = """
            SELECT id FROM lotteries
            WHERE status = 'CLOSED' AND draw_at <= now() AND deleted_at IS NULL
            ORDER BY draw_at, id
            """)
    List<Long> findDrawDueIds();

    // Note: 여러 인스턴스가 동시에 집어도 뒤 트랜잭션은 행 잠금을 기다린 뒤 이미 DRAWN이라 0행이 되므로 추첨이 한 번만 실행된다.
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE lotteries SET status = 'DRAWN', updated_at = now()
            WHERE id = :id AND status = 'CLOSED' AND draw_at <= now()
            """)
    int markDrawn(@Param("id") Long id);

    @Query(nativeQuery = true, value = """
            SELECT id FROM lotteries
            WHERE status = 'DRAWN' AND now() < draw_at + interval '30 minutes' AND deleted_at IS NULL
            ORDER BY id
            """)
    List<Long> findSuccessionOpenIds();

    // Note: 이 행 잠금으로 같은 추첨의 승계를 줄 세워 좌석 수를 넘겨 당첨시키지 않고, 승계 마감 조건도 같은 문장에 둬 마감 뒤에는 잠금을 얻지 못한다(D13).
    @Query(nativeQuery = true, value = """
            SELECT seat_count FROM lotteries
            WHERE id = :id AND status = 'DRAWN' AND now() < draw_at + interval '30 minutes'
            FOR UPDATE SKIP LOCKED
            """)
    List<Integer> lockForSuccession(@Param("id") Long id);

    @Query(nativeQuery = true, value = """
            SELECT l.id FROM lotteries l
            WHERE l.status = 'DRAWN' AND l.deleted_at IS NULL
              AND NOT EXISTS (SELECT 1 FROM lottery_entries e
                              WHERE e.lottery_id = l.id AND e.status IN ('WON', 'WAITLISTED'))
            ORDER BY l.draw_at, l.id
            """)
    List<Long> findFinishDueIds();

    @Query(nativeQuery = true, value = """
            SELECT * FROM lotteries WHERE id = :id AND status = 'DRAWN' AND deleted_at IS NULL
            FOR UPDATE SKIP LOCKED
            """)
    Optional<Lottery> lockForFinish(@Param("id") Long id);

    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE lotteries SET status = 'DONE', updated_at = now()
            WHERE id = :id AND status = 'DRAWN'
            """)
    int markDone(@Param("id") Long id);

    @Query(nativeQuery = true, value = "SELECT count(*) FROM lotteries WHERE status <> 'DONE' AND deleted_at IS NULL")
    long countUnfinished();

    @Query(nativeQuery = true, value = """
            SELECT COALESCE(CAST(EXTRACT(EPOCH FROM now() - MIN(draw_at)) AS bigint), 0)
            FROM lotteries WHERE status = 'DRAWN' AND deleted_at IS NULL
            """)
    long findOldestUnfinishedSeconds();

    // 추첨 트랜잭션이 다른 트랜잭션의 잠금을 오래 기다리지 않게 하며, 트랜잭션이 끝나면 원래 값으로 돌아간다
    @Query(nativeQuery = true, value = "SELECT set_config('lock_timeout', '3s', true)")
    String setLocalLockTimeout();
}
