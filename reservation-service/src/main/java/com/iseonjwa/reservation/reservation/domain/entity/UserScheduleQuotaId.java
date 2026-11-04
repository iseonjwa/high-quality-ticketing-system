package com.iseonjwa.reservation.reservation.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;

@Embeddable
public record UserScheduleQuotaId(@Column(name = "user_id") Long userId, @Column(name = "schedule_id") Long scheduleId)
        implements Serializable {}
