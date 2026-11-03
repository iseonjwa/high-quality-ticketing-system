package com.iseonjwa.reservation.reservation.infrastructure.persistence;

import com.iseonjwa.reservation.reservation.domain.entity.DeadLetter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

interface SpringDataDeadLetterRepository extends JpaRepository<DeadLetter, Long> {

    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO dead_letters (source_topic, source_partition, source_offset, message_key, event_key, event_id,
                                      payload_raw, reason, status, created_at)
            VALUES (:sourceTopic, :sourcePartition, :sourceOffset, :messageKey, :eventKey, :eventId,
                    :payloadRaw, :reason, 'OPEN', :createdAt)
            ON CONFLICT (event_key) DO NOTHING
            """)
    int insertOpenIfAbsent(@Param("sourceTopic") String sourceTopic, @Param("sourcePartition") Integer sourcePartition,
            @Param("sourceOffset") Long sourceOffset, @Param("messageKey") String messageKey,
            @Param("eventKey") String eventKey, @Param("eventId") UUID eventId,
            @Param("payloadRaw") String payloadRaw, @Param("reason") String reason,
            @Param("createdAt") Instant createdAt);

    // Note: 같은 eventId가 DLQ에 두 번 와서 먼저 온 쪽이 OPEN으로 남았다면, 나중의 재처리 성공으로 닫는다.
    // 이미 RESOLVED인 행은 WHERE에 걸리지 않아 0행이다
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO dead_letters (source_topic, source_partition, source_offset, message_key, event_key, event_id,
                                      payload_raw, status, resolution, created_at, resolved_at)
            VALUES (:sourceTopic, :sourcePartition, :sourceOffset, :messageKey, :eventKey, :eventId,
                    :payloadRaw, 'RESOLVED', :resolution, :resolvedAt, :resolvedAt)
            ON CONFLICT (event_key) DO UPDATE
               SET status = 'RESOLVED', resolution = :resolution, resolved_at = :resolvedAt
             WHERE dead_letters.status = 'OPEN'
            """)
    int insertOrResolve(@Param("sourceTopic") String sourceTopic, @Param("sourcePartition") Integer sourcePartition,
            @Param("sourceOffset") Long sourceOffset, @Param("messageKey") String messageKey,
            @Param("eventKey") String eventKey, @Param("eventId") UUID eventId,
            @Param("payloadRaw") String payloadRaw, @Param("resolution") String resolution,
            @Param("resolvedAt") Instant resolvedAt);
}
