package com.iseonjwa.payment.refund.domain;

import java.util.UUID;

/** 저장할 DLQ 메시지. {@code eventId}·{@code eventType}은 해석했을 때만 있다. */
public record NewDeadLetter(String sourceTopic, Integer sourcePartition, Long sourceOffset, String messageKey,
                            String eventKey, UUID eventId, String eventType, String payloadRaw, String reason) {}
