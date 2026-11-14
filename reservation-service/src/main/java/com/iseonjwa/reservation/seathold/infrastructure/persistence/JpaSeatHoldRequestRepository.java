package com.iseonjwa.reservation.seathold.infrastructure.persistence;

import com.iseonjwa.reservation.seathold.domain.entity.SeatHoldRequest;
import com.iseonjwa.reservation.seathold.domain.repository.EntryTokenAlreadyUsedException;
import com.iseonjwa.reservation.seathold.domain.repository.SeatHoldRequestRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaSeatHoldRequestRepository implements SeatHoldRequestRepository {
    private static final String ENTRY_TOKEN_CONSTRAINT = "uq_seat_hold_requests_entry_token_jti";

    private final SpringDataSeatHoldRequestRepository repository;

    public JpaSeatHoldRequestRepository(SpringDataSeatHoldRequestRepository repository) {
        this.repository = repository;
    }

    @Override
    public SeatHoldRequest save(SeatHoldRequest request) {
        try {
            return repository.saveAndFlush(request);
        } catch (DataIntegrityViolationException exception) {
            if (violates(exception, ENTRY_TOKEN_CONSTRAINT)) {
                throw new EntryTokenAlreadyUsedException(exception);
            }
            throw exception;
        }
    }

    @Override
    public int markHeld(UUID requestId, Long reservationId, Instant now, Long actorId) {
        return repository.markHeld(requestId, reservationId, now, actorId);
    }

    @Override
    public Optional<Instant> lockRequestedCreatedAt(UUID requestId) {
        return repository.lockRequestedCreatedAt(requestId);
    }

    @Override
    public int markRejected(UUID requestId, String reasonCode, Instant now) {
        return repository.markRejected(requestId, reasonCode, now);
    }

    @Override
    public int rejectTimedOut(Instant createdBefore, Instant now, int limit) {
        return repository.rejectTimedOut(createdBefore, now, limit);
    }

    @Override
    public Optional<SeatHoldRequest> findById(UUID requestId) {
        return repository.findById(requestId);
    }

    @Override
    public boolean existsActiveByEntryTokenJti(String entryTokenJti) {
        return repository.existsActiveByEntryTokenJti(entryTokenJti);
    }

    private static boolean violates(Throwable exception, String constraintName) {
        for (var cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return constraintName.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        return false;
    }
}
