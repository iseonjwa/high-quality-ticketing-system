package com.iseonjwa.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * 업무 테이블 공통 컬럼 (SA 12-1).
 * 시각은 UTC({@link Instant}, DB는 TIMESTAMPTZ), 사람은 사용자 ID다. 요청이면 {@code X-User-Id}로 채우고
 * Kafka 소비자·스케줄러가 바꾸면 NULL(시스템)이다.
 * <p>
 * 쓰는 서비스는 {@code @EnableJpaAuditing(auditorAwareRef = "headerAuditorAware")}와
 * {@link com.iseonjwa.common.audit.HeaderAuditorAware} 빈을 등록한다.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @LastModifiedBy
    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private Long deletedBy;

    /**
     * 논리 삭제. 운영자가 내리는 데이터(공연장·공연·회차)에만 쓴다.
     * 예매·결제·좌석·티켓은 삭제하지 않고 상태 전이로 처리한다 (SA 12-1).
     */
    protected void markDeleted(Long deletedBy) {
        this.deletedAt = Instant.now();
        this.deletedBy = deletedBy;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public Long getDeletedBy() {
        return deletedBy;
    }
}
