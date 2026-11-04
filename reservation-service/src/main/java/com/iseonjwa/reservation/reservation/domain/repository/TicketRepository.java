package com.iseonjwa.reservation.reservation.domain.repository;

import com.iseonjwa.reservation.reservation.domain.entity.Ticket;

import java.util.List;

public interface TicketRepository {
    List<Ticket> saveAll(List<Ticket> tickets);
}
