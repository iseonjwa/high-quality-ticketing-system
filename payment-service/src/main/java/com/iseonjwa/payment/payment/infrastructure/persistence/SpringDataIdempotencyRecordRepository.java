package com.iseonjwa.payment.payment.infrastructure.persistence;

import com.iseonjwa.payment.payment.domain.IdempotencyRecord;
import com.iseonjwa.payment.payment.domain.IdempotencyRecordId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

interface SpringDataIdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, IdempotencyRecordId> {
    // Note: 동시에 같은 새 키가 오면 한쪽만 들어간다. 확인 뒤 삽입이 아니라 삽입을 먼저 시도해 경합을 기본키에 맡긴다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            INSERT INTO idempotency_record (user_id, idem_key, request_hash, resource_id, link_type, expires_at, created_at)
            VALUES (:userId, :idemKey, :requestHash, :paymentId, 'ALIAS', :expiresAt, :now)
            ON CONFLICT (user_id, idem_key) DO NOTHING
            """)
    int linkAlias(@Param("userId") Long userId, @Param("idemKey") UUID idemKey,
            @Param("requestHash") String requestHash, @Param("paymentId") Long paymentId,
            @Param("expiresAt") Instant expiresAt, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE idempotency_record SET response_status = :status, response_body = CAST(:body AS jsonb)
            WHERE user_id = :userId AND idem_key = :idemKey AND link_type = 'ORIGIN' AND response_status IS NULL
            """)
    int saveResponse(@Param("userId") Long userId, @Param("idemKey") UUID idemKey, @Param("status") int status,
            @Param("body") String body);
}
