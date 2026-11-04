package com.iseonjwa.payment.reconciliation.application;

import java.time.Instant;
import java.util.Set;

/** 결제 DB에서 예매 서비스에 물어볼 예매 ID를 읽는다(짧은 읽기, 트랜잭션 밖). */
public interface ReservationTargets {
    /**
     * @param since 이 시각 이후 갱신된 {@code APPROVED}·{@code REFUNDED} 결제의 예매와, RC-1~RC-6의 {@code OPEN} 행이 가리키는
     *              예매를 합친다
     */
    Set<Long> reservationIds(Instant since);
}
