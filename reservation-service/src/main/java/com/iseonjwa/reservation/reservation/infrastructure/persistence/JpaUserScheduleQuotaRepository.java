package com.iseonjwa.reservation.reservation.infrastructure.persistence;

import com.iseonjwa.reservation.reservation.domain.entity.UserScheduleQuota;
import com.iseonjwa.reservation.reservation.domain.entity.UserScheduleQuotaId;
import com.iseonjwa.reservation.reservation.domain.repository.UserScheduleQuotaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public class JpaUserScheduleQuotaRepository implements UserScheduleQuotaRepository {
    private final SpringDataUserScheduleQuotaRepository repository;

    public JpaUserScheduleQuotaRepository(SpringDataUserScheduleQuotaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<UserScheduleQuota> findById(UserScheduleQuotaId id) {
        return repository.findById(id);
    }

    @Override
    public void addHeldCount(UserScheduleQuotaId id, int quantity, Instant now, Long actorId) {
        repository.addHeldCount(id.userId(), id.scheduleId(), quantity, now, actorId);
    }

    @Override
    public int addHeldCountWithinLimit(UserScheduleQuotaId id, int quantity, int maxPerUser, Instant now,
            Long actorId) {
        repository.insertEmptyIfAbsent(id.userId(), id.scheduleId(), now, actorId);
        return repository.addHeldCountWithinLimit(id.userId(), id.scheduleId(), quantity, maxPerUser, now, actorId);
    }

    @Override
    public int subtractHeldCount(UserScheduleQuotaId id, int quantity, Instant now, Long actorId) {
        return repository.subtractHeldCount(id.userId(), id.scheduleId(), quantity, now, actorId);
    }
}
