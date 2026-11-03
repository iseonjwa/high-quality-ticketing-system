package com.iseonjwa.payment.ops.infrastructure;

import com.iseonjwa.payment.ops.domain.ManualActionLog;
import com.iseonjwa.payment.ops.domain.ManualActionLogRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

/**
 * JDBC로 {@code manual_action_logs}를 쓴다. 호출한 트랜잭션에 참여한다.
 *
 * <p>Note: 엔티티를 두지 않는다. 한 번 넣고 다시 읽지 않는 기록용 테이블이라 영속성 컨텍스트가 줄 이득이 없고, 수정
 * 메서드가 없는 것이 append-only 의도를 코드에서도 보이게 한다.
 */
@Repository
public class JdbcManualActionLogRepository implements ManualActionLogRepository {
    private final JdbcTemplate jdbc;

    public JdbcManualActionLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(ManualActionLog log) {
        jdbc.update("""
                INSERT INTO manual_action_logs (command, target_type, target_id, from_status, to_status, result,
                                                plan_hash, pg_evidence, external_ref, operator, approver, reason,
                                                performed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                """, log.command(), log.targetType().name(), log.targetId(), log.fromStatus(), log.toStatus(),
                log.result().name(), log.planHash(), log.pgEvidence(), log.externalRef(), log.operator(),
                log.approver(), log.reason(), Timestamp.from(log.performedAt()));
    }
}
