package com.iseonjwa.reservation.queue.presentation.dto.response;

import com.iseonjwa.reservation.queue.application.QueueTokenResult;
import com.iseonjwa.reservation.queue.domain.AdmissionStatus;

import java.time.OffsetDateTime;
import java.time.ZoneId;

public record QueueTokenResponse(AdmissionStatus status, String entryToken, OffsetDateTime entryTokenExpiresAt) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static QueueTokenResponse from(QueueTokenResult queueTokenResult) {
        return new QueueTokenResponse(
                queueTokenResult.status(),
                queueTokenResult.entryToken(),
                queueTokenResult.entryTokenExpiresAt().atZone(KST).toOffsetDateTime());
    }

}
