package com.iseonjwa.payment.reconciliation.domain;

import java.time.Duration;

/**
 * 규칙이 대상을 고르는 기준.
 *
 * @param allowedLag  허용 지연 L. 마지막 갱신이 이보다 가까우면 판단하지 않는다
 * @param window      결제를 고르는 기간(마지막 갱신 기준)
 * @param eventWindow 확정 전이와 Outbox 행을 대조하는 기간
 */
public record ReconciliationPolicy(Duration allowedLag, Duration window, Duration eventWindow) {}
