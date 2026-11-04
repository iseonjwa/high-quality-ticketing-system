package com.iseonjwa.common.messaging.outbox;

import java.time.Duration;

/**
 * 릴레이 설정. 값은 {@code messaging.outbox.*}로 받는다.
 *
 * @param batchSize   한 번에 집는 행 수
 * @param lease       집은 행을 다른 실행이 다시 집지 못하는 시간
 * @param sendTimeout 한 번의 실행이 브로커 확인을 기다리는 최대 시간
 */
public record OutboxProperties(int batchSize, Duration lease, Duration sendTimeout) {
    public OutboxProperties {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("messaging.outbox.batch-size는 1 이상이어야 합니다");
        }
        if (sendTimeout.isNegative() || sendTimeout.isZero()) {
            throw new IllegalArgumentException("messaging.outbox.send-timeout은 0보다 커야 합니다");
        }
        // Note: 임대가 전송 시한보다 짧으면 전송이 아직 진행 중인데 다른 실행이 같은 행을 다시 집어
        // 순서가 뒤섞인 중복이 생긴다. 시한의 2배 이상으로 둔다(events-and-reliability.md 3절)
        if (lease.compareTo(sendTimeout.multipliedBy(2)) < 0) {
            throw new IllegalArgumentException("messaging.outbox.lease는 send-timeout의 2배 이상이어야 합니다");
        }
    }
}
