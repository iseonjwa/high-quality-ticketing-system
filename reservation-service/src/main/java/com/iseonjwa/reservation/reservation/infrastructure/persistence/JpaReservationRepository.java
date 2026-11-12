package com.iseonjwa.reservation.reservation.infrastructure.persistence;

import com.iseonjwa.reservation.global.vo.Money;
import com.iseonjwa.reservation.reservation.domain.entity.Reservation;
import com.iseonjwa.reservation.reservation.domain.entity.ReservationStatus;
import com.iseonjwa.reservation.reservation.domain.repository.ReservationRepository;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaReservationRepository implements ReservationRepository {
    private final SpringDataReservationRepository repository;

    public JpaReservationRepository(SpringDataReservationRepository repository) {
        this.repository = repository;
    }

    @Override
    public Reservation save(Reservation reservation) {
        return repository.saveAndFlush(reservation);
    }

    @Override
    public Optional<Reservation> findById(Long id) {
        return repository.findById(id);
    }

    @Override
    public Optional<Reservation> findByRequestId(UUID requestId) {
        return repository.findByRequestId(requestId);
    }

    @Override
    public List<MyReservationRow> findMine(Long userId, int page, int size) {
        return repository.findMineRows(userId, size, (long) page * size).stream()
                .map(row -> new MyReservationRow(((Number) row[0]).longValue(), (String) row[1], (String) row[2],
                        (Instant) row[3], ReservationStatus.valueOf((String) row[4]), Money.of((BigDecimal) row[5])))
                .toList();
    }

    @Override
    public long countByUserId(Long userId) {
        return repository.countByUserId(userId);
    }

    @Override
    public List<Reservation> findAllByIds(List<Long> ids) {
        return repository.findByIdInOrderByIdAsc(ids);
    }

    @Override
    public List<Reservation> findSettledChanges(Instant from, Instant to, long cursor, int limit) {
        return repository.findSettledChanges(from, to, cursor, limit);
    }

    @Override
    public List<Long> findExpiredHeldIds(Instant now, int limit) {
        return repository.findExpiredHeldIds(now, limit);
    }

    @Override
    public Optional<Long> lockNextExpiredHeldId(Instant now, Collection<Long> excludedIds) {
        var excluded = new ArrayList<Long>(excludedIds);
        excluded.add(0L);
        return repository.lockNextExpiredHeldId(now, excluded);
    }

    @Override
    public int expireHeld(Long reservationId, Instant now, Long actorId) {
        return repository.expireHeld(reservationId, now, actorId);
    }

    @Override
    public int confirmHeld(Long reservationId, Long paymentId, Instant now) {
        return repository.confirmHeld(reservationId, paymentId, now);
    }

    @Override
    public int releaseHeld(Long reservationId, Instant now) {
        return repository.releaseHeld(reservationId, now);
    }

    @Override
    public int cancelConfirmed(Long reservationId, Instant now, Long actorId) {
        return repository.cancelConfirmed(reservationId, now, actorId);
    }

    @Override
    public int extendLotteryHoldOnce(Long reservationId, Instant now, Instant limit, Long actorId) {
        return repository.extendLotteryHoldOnce(reservationId, now, limit, actorId);
    }
}
