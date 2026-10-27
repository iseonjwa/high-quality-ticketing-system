package iseonjwa.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;

import java.time.Instant;
import java.util.Objects;

@MappedSuperclass
@EntityListeners(CommonAuditingEntityListener.class)
public abstract class BaseEntity {
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private Long deletedBy;

    protected BaseEntity() {}

    void recordCreation(Instant now, Long actorId) {
        createdAt = now;
        createdBy = actorId;
        recordModification(now, actorId);
    }

    void recordModification(Instant now, Long actorId) {
        updatedAt = now;
        updatedBy = actorId;
    }

    // 논리 삭제가 허용된 도메인에서만 호출한다. 조회 필터는 각 도메인이 정한다.
    protected void markDeleted(Instant at, Long actorId) {
        deletedAt = Objects.requireNonNull(at);
        deletedBy = actorId;
    }

    public Instant getCreatedAt() { return createdAt; }
    public Long getCreatedBy() { return createdBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getUpdatedBy() { return updatedBy; }
    public Instant getDeletedAt() { return deletedAt; }
    public Long getDeletedBy() { return deletedBy; }
}
