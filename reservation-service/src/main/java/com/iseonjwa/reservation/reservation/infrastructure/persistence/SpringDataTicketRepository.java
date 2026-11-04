package com.iseonjwa.reservation.reservation.infrastructure.persistence;

import com.iseonjwa.reservation.reservation.domain.entity.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataTicketRepository extends JpaRepository<Ticket, Long> {
}
