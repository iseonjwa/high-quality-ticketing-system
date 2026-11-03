package com.iseonjwa.payment.refund.infrastructure.persistence;

import com.iseonjwa.payment.refund.domain.DeadLetter;
import com.iseonjwa.payment.refund.domain.DeadLetterRepository;
import com.iseonjwa.payment.refund.domain.DeadLetterResolution;
import com.iseonjwa.payment.refund.domain.DeadLetterStatus;
import com.iseonjwa.payment.refund.domain.NewDeadLetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC로 {@code dead_letters}를 읽고 쓴다.
 *
 * <p>Note: 엔티티를 두지 않는다. 행을 만들 때만 {@code ON CONFLICT (event_key) DO NOTHING}과 {@code RETURNING}이 필요하고,
 * 갱신은 상태 조건이 붙은 UPDATE뿐이라 영속성 컨텍스트가 줄 이득이 없다. 같은 트랜잭션의 JPA 쓰기(환불 생성)와
 * 한 연결을 쓴다.
 */
@Repository
public class JdbcDeadLetterRepository implements DeadLetterRepository {
    private static final RowMapper<DeadLetter> MAPPER = JdbcDeadLetterRepository::map;

    private final JdbcTemplate jdbc;

    public JdbcDeadLetterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Long> insertOpen(NewDeadLetter deadLetter, Instant now) {
        // 충돌하면 RETURNING이 행을 돌려주지 않는다
        var ids = jdbc.queryForList("""
                INSERT INTO dead_letters (source_topic, source_partition, source_offset, message_key, event_key,
                                          event_id, event_type, payload_raw, reason, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?)
                ON CONFLICT (event_key) DO NOTHING
                RETURNING id
                """, Long.class, deadLetter.sourceTopic(), deadLetter.sourcePartition(), deadLetter.sourceOffset(),
                deadLetter.messageKey(), deadLetter.eventKey(), deadLetter.eventId(), deadLetter.eventType(),
                deadLetter.payloadRaw(), deadLetter.reason(), Timestamp.from(now));
        return ids.stream().findFirst();
    }

    @Override
    public boolean updateOpenReason(Long id, String reason) {
        return jdbc.update("UPDATE dead_letters SET reason = ? WHERE id = ? AND status = 'OPEN'", reason, id) == 1;
    }

    @Override
    public boolean resolve(Long id, DeadLetterResolution resolution, Long refundId, Instant now) {
        return jdbc.update("""
                UPDATE dead_letters SET status = 'RESOLVED', resolution = ?, refund_id = ?, resolved_at = ?
                WHERE id = ? AND status = 'OPEN'
                """, resolution.name(), refundId, Timestamp.from(now), id) == 1;
    }

    @Override
    public Optional<DeadLetter> findById(Long id) {
        return jdbc.query("SELECT * FROM dead_letters WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    @Override
    public Optional<DeadLetter> findByEventKey(String eventKey) {
        return jdbc.query("SELECT * FROM dead_letters WHERE event_key = ?", MAPPER, eventKey).stream().findFirst();
    }

    @Override
    public long countOpen() {
        return jdbc.queryForObject("SELECT count(*) FROM dead_letters WHERE status = 'OPEN'", Long.class);
    }

    private static DeadLetter map(ResultSet rs, int rowNum) throws SQLException {
        var resolution = rs.getString("resolution");
        return new DeadLetter(rs.getLong("id"), rs.getString("source_topic"), (Integer) rs.getObject("source_partition"),
                (Long) rs.getObject("source_offset"), rs.getString("message_key"), rs.getString("event_key"),
                rs.getObject("event_id", UUID.class), rs.getString("event_type"), rs.getString("payload_raw"),
                rs.getString("reason"), DeadLetterStatus.valueOf(rs.getString("status")),
                resolution == null ? null : DeadLetterResolution.valueOf(resolution), (Long) rs.getObject("refund_id"),
                instant(rs, "created_at"), instant(rs, "resolved_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
