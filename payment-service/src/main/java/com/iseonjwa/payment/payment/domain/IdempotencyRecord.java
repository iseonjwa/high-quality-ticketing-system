package com.iseonjwa.payment.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 멱등키 한 건의 기록(SA 12-5, 0017·0019). 기록용 테이블이라 BaseEntity를 상속하지 않는다.
 * 응답 컬럼은 TX2에서 저장소의 조건부 UPDATE로만 채운다.
 */
@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecord implements Persistable<IdempotencyRecordId> {
    // 멱등 기록 보관 기간(SA 12-1)
    static final Duration RETENTION = Duration.ofHours(24);

    @EmbeddedId
    private IdempotencyRecordId id;

    // 요청 본문의 SHA-256. 같은 키에 다른 본문이 오면 409로 거절하는 근거다
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Column(name = "resource_id", nullable = false, updatable = false)
    private Long resourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "link_type", nullable = false, updatable = false, length = 10)
    private IdempotencyLinkType linkType;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "response_status", updatable = false)
    private Integer responseStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", updatable = false)
    private String responseBody;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // Note: ID를 직접 정하는 엔티티는 Spring Data가 새 엔티티로 보지 않고 merge로 저장한다. merge는 같은 기본키가 이미
    // 있으면 위반 없이 넘어가 "같은 키는 한 번만"이라는 계약이 조용히 깨진다. 저장 전까지 새 엔티티로 표시해 persist로 보낸다.
    @Transient
    private boolean isNew = true;

    protected IdempotencyRecord() {}

    @Override
    public IdempotencyRecordId getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    private IdempotencyRecord(Long userId, UUID idemKey, String requestHash, Long resourceId,
            IdempotencyLinkType linkType, Instant now) {
        this.id = new IdempotencyRecordId(userId, idemKey);
        this.requestHash = requestHash;
        this.resourceId = resourceId;
        this.linkType = linkType;
        this.expiresAt = now.plus(RETENTION);
        this.createdAt = now;
    }

    public static IdempotencyRecord origin(Long userId, UUID idemKey, String requestHash, Long paymentId,
            Instant now) {
        return new IdempotencyRecord(userId, idemKey, requestHash, paymentId, IdempotencyLinkType.ORIGIN, now);
    }

    public Long getUserId() { return id.userId(); }
    public UUID getIdemKey() { return id.idemKey(); }
    public String getRequestHash() { return requestHash; }
    public Long getResourceId() { return resourceId; }
    public IdempotencyLinkType getLinkType() { return linkType; }
    public Integer getResponseStatus() { return responseStatus; }
    public String getResponseBody() { return responseBody; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }

    // ORIGIN이고 TX2가 응답을 저장했으면 같은 키 재요청에 그대로 재생한다
    public boolean hasStoredResponse() {
        return linkType == IdempotencyLinkType.ORIGIN && responseStatus != null;
    }
}
