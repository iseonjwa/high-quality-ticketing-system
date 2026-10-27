package com.iseonjwa.common.persistence;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Clock;

public final class CommonAuditingEntityListener {
    private final CurrentActorProvider actors;
    private final Clock clock;

    public CommonAuditingEntityListener(CurrentActorProvider actors,
            @Qualifier("commonAuditingClock") Clock clock) {
        this.actors = actors;
        this.clock = clock;
    }

    @PrePersist
    public void beforeInsert(BaseEntity entity) {
        entity.recordCreation(clock.instant(), actors.currentActorId().orElse(null));
    }

    @PreUpdate
    public void beforeUpdate(BaseEntity entity) {
        // 시스템 수정도 이전 사용자를 남기지 않고 NULL을 명시적으로 기록한다.
        entity.recordModification(clock.instant(), actors.currentActorId().orElse(null));
    }
}
