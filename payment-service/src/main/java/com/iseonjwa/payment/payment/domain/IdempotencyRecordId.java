package com.iseonjwa.payment.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
public record IdempotencyRecordId(@Column(name = "user_id") Long userId, @Column(name = "idem_key") UUID idemKey)
        implements Serializable {}
