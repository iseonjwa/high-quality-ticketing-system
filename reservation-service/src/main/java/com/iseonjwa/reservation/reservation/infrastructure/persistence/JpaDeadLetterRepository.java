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

    // Note: 같은 DLQ 메시지가 다시 와도 예외 없이 건너뛰려고 JPA save 대신 ON CONFLICT DO NOTHING으로 넣는다
    @Override
    @Transactional
    public boolean saveIfAbsent(DeadLetter deadLetter) {
        return repository.insertIfAbsent(deadLetter.getSourceTopic(), deadLetter.getSourcePartition(),
                deadLetter.getSourceOffset(), deadLetter.getMessageKey(), deadLetter.getEventKey(),
                deadLetter.getEventId(), deadLetter.getPayloadRaw(), deadLetter.getReason(),
                deadLetter.getStatus().name(), deadLetter.getResolution(), deadLetter.getCreatedAt(),
                deadLetter.getResolvedAt()) == 1;
    }
}
