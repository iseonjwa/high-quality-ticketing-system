package com.iseonjwa.payment.reconciliation.domain;

import java.time.Instant;

/**
 * 예매 서비스에서 한 시점에 읽은 예매 하나. 개인정보는 없다.
 *
 * @param totalAmount   원 단위 정수
 * @param paymentId     확정 전이면 null
 * @param holdExpiresAt 선점 만료 시각. 없으면 null
 */
public record ReservationSnapshot(long reservationId, long userId, ReservationStatus status, long totalAmount,
        Long paymentId, Instant holdExpiresAt, Instant updatedAt) {}
