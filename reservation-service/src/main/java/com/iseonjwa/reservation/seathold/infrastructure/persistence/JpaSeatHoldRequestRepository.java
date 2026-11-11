package com.iseonjwa.reservation.seathold.infrastructure.persistence;

import com.iseonjwa.reservation.seathold.domain.entity.SeatHoldRequest;
import com.iseonjwa.reservation.seathold.domain.repository.SeatHoldRequestRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaSeatHoldRequestRepository implements SeatHoldRequestRepository {
    private final SpringDataSeatHoldRequestRepository repository;
    private final JdbcTemplate jdbc;

    public JpaSeatHoldRequestRepository(SpringDataSeatHoldRequestRepository repository, JdbcTemplate jdbc) {
        this.repository = repository;
        this.jdbc = jdbc;
    }

    @Override
    public SeatHoldRequest save(SeatHoldRequest request) {
        return repository.saveAndFlush(request);
    }

    @Override
    public int markHeld(UUID requestId, Long reservationId, Instant now, Long actorId) {
        return repository.markHeld(requestId, reservationId, now, actorId);
    }

    @Override
    public Optional<SeatHoldRequest> findById(UUID requestId) {
        return repository.findById(requestId);
    }

    // Note: Spring Data의 existsById는 저장소 기본 트랜잭션(readOnly)을 열어 BEGIN·COMMIT 왕복이 붙는다.
    // JdbcTemplate는 바깥 트랜잭션이 없으면 자동 커밋 한 문장으로 끝난다
    @Override
    public boolean existsById(UUID requestId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM seat_hold_requests WHERE request_id = ?)", Boolean.class, requestId));
    }
}
