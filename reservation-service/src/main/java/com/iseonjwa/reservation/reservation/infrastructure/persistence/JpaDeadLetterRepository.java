package com.iseonjwa.reservation.reservation.infrastructure.persistence;

import com.iseonjwa.reservation.reservation.domain.entity.DeadLetter;
import com.iseonjwa.reservation.reservation.domain.repository.DeadLetterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class JpaDeadLetterRepository implements DeadLetterRepository {
    private final SpringDataDeadLetterRepository repository;

    // Note: 같은 DLQ 메시지가 다시 와도 예외 없이 건너뛰려고 JPA save 대신 ON CONFLICT로 넣는다
    @Override
    @Transactional
    public boolean saveOpenIfAbsent(DeadLetter deadLetter) {
        return repository.insertOpenIfAbsent(deadLetter.getSourceTopic(), deadLetter.getSourcePartition(),
                deadLetter.getSourceOffset(), deadLetter.getMessageKey(), deadLetter.getEventKey(),
                deadLetter.getEventId(), deadLetter.getPayloadRaw(), deadLetter.getReason(),
                deadLetter.getCreatedAt()) == 1;
    }

    @Override
    @Transactional
    public boolean saveResolved(DeadLetter deadLetter) {
        return repository.insertOrResolve(deadLetter.getSourceTopic(), deadLetter.getSourcePartition(),
                deadLetter.getSourceOffset(), deadLetter.getMessageKey(), deadLetter.getEventKey(),
                deadLetter.getEventId(), deadLetter.getPayloadRaw(), deadLetter.getResolution(),
                deadLetter.getResolvedAt()) == 1;
    }
}
