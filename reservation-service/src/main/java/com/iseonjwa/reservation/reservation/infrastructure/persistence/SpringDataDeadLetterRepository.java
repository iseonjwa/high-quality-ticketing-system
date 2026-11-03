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
                                      payload_raw, reason, status, resolution, created_at, resolved_at)
            VALUES (:sourceTopic, :sourcePartition, :sourceOffset, :messageKey, :eventKey, :eventId,
                    :payloadRaw, :reason, :status, :resolution, :createdAt, :resolvedAt)
            ON CONFLICT (event_key) DO NOTHING
            """)
    int insertIfAbsent(@Param("sourceTopic") String sourceTopic, @Param("sourcePartition") Integer sourcePartition,
            @Param("sourceOffset") Long sourceOffset, @Param("messageKey") String messageKey,
            @Param("eventKey") String eventKey, @Param("eventId") UUID eventId,
            @Param("payloadRaw") String payloadRaw, @Param("reason") String reason, @Param("status") String status,
            @Param("resolution") String resolution, @Param("createdAt") Instant createdAt,
            @Param("resolvedAt") Instant resolvedAt);
}
