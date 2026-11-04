package com.iseonjwa.reservation.seathold.infrastructure.persistence;

import com.iseonjwa.reservation.seathold.domain.entity.SeatHoldRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface SpringDataSeatHoldRequestRepository extends JpaRepository<SeatHoldRequest, UUID> {}
