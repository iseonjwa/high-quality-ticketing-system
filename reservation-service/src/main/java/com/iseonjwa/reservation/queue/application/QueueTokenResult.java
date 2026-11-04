package com.iseonjwa.reservation.queue.application;

import com.iseonjwa.reservation.queue.domain.AdmissionStatus;

import java.time.Instant;

public record QueueTokenResult(AdmissionStatus status, String entryToken, Instant entryTokenExpiresAt) {
}
