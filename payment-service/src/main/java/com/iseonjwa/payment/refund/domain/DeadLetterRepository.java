package com.iseonjwa.payment.refund.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * DLQ 메시지 저장소. 쓰기는 호출한 트랜잭션에 참여한다.
 */
public interface DeadLetterRepository {
    /**
     * {@code OPEN} 행으로 저장한다. {@code event_key}가 이미 있으면 {@code ON CONFLICT DO NOTHING}이라 저장하지 않는다.
     *
     * @return 저장한 행의 id. 같은 {@code event_key}가 이미 있어 저장하지 않았으면 비어 있다(이미 처리한 메시지)
     */
    Optional<Long> insertOpen(NewDeadLetter deadLetter, Instant now);

    /** {@code OPEN} 행의 사유만 바꾼다. */
    boolean updateOpenReason(Long id, String reason);

    /** {@code OPEN} 행을 {@code RESOLVED}로 닫는다. 이미 닫혔으면 아무것도 하지 않고 false다. */
    boolean resolve(Long id, DeadLetterResolution resolution, Long refundId, Instant now);

    Optional<DeadLetter> findByEventKey(String eventKey);

    long countOpen();
}
