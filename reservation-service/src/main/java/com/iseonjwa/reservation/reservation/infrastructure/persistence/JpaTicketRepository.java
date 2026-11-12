package com.iseonjwa.reservation.reservation.infrastructure.persistence;

import com.iseonjwa.reservation.reservation.domain.entity.Ticket;
import com.iseonjwa.reservation.reservation.domain.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class JpaTicketRepository implements TicketRepository {
    private final SpringDataTicketRepository repository;

    @Override
    public List<Ticket> saveAll(List<Ticket> tickets) {
        return repository.saveAllAndFlush(tickets);
    }

    @Override
    public int voidIssuedByReservation(Long reservationId, Instant now, Long actorId) {
        return repository.voidIssuedByReservation(reservationId, now, actorId);
    }
}
