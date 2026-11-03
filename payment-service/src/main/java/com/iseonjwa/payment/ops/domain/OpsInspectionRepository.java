package com.iseonjwa.payment.ops.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Ops 명령이 대상을 조사하는 읽기 전용 조회. 결제·환불·Outbox·{@code dead_letters}·감사 기록을 한 화면에 모으려고
 * 테이블을 가로질러 읽는다. 쓰는 메서드는 없다.
 */
public interface OpsInspectionRepository {
    Optional<PaymentView> findPayment(long paymentId);

    Optional<Long> findPaymentIdByReservationId(long reservationId);

    Optional<Long> findPaymentIdByRefundId(long refundId);

    List<HistoryView> findHistories(long paymentId);

    Optional<RefundView> findRefundByPaymentId(long paymentId);

    Optional<OutboxView> findOutbox(long outboxId);

    /** 같은 예매 키({@code message_key})의 Outbox 행을 id 순으로 돌려준다. */
    List<OutboxView> findOutboxByKey(String messageKey);

    /** 같은 키에서 이 행과 순서로 얽힌 행의 수. */
    OutboxBacklog findOutboxBacklog(OutboxView row);

    List<DeadLetterView> findDeadLettersByKey(String messageKey);

    /** 주어진 대상들에 대한 감사 기록을 오래된 순으로 돌려준다. 비어 있는 목록은 조건에서 뺀다. */
    List<ManualLogView> findManualActionLogs(List<Long> paymentIds, List<Long> refundIds, List<Long> outboxIds,
            List<Long> deadLetterIds);

    record PaymentView(long id, String paymentNo, long reservationId, long userId, BigDecimal amount, String currency,
            String method, String status, UUID pgOrderId, String pgTxId, BigDecimal pgApprovedAmount,
            String declineCode, int unknownRetryCount, Instant nextCheckAt, Instant approvedAt, Instant refundedAt,
            Instant createdAt, Instant updatedAt) {
    }

    record HistoryView(long id, String targetType, String fromStatus, String toStatus, String reason, String actor,
            Instant createdAt) {
    }

    record RefundView(long id, long paymentId, UUID pgRefundId, BigDecimal amount, String reason, String source,
            String status, int attempt, Instant nextCheckAt, Instant pgRequestedAt, String pgRefundRef,
            Instant refundedAt, Instant createdAt, Instant updatedAt) {
    }

    record OutboxView(long id, UUID eventId, String topic, String messageKey, String eventType, String status,
            int attempts, Instant nextAttemptAt, String lastError, Instant createdAt, Instant sentAt) {
    }

    /**
     * @param pendingBehind 같은 키에서 이 행보다 뒤에 있는 PENDING 행 수. 이 행이 FAILED인 동안 막혀 있다
     * @param failedBehind  같은 키에서 뒤에 있는 다른 FAILED 행 수
     * @param failedAhead   같은 키에서 앞에 있는 다른 FAILED 행 수. 있으면 이 행을 되살려도 앞선 행이 풀려야 나간다
     */
    record OutboxBacklog(long pendingBehind, long failedBehind, long failedAhead) {
    }

    record DeadLetterView(long id, String sourceTopic, String messageKey, String eventType, String status,
            String resolution, String reason, String payloadHead, Instant createdAt) {
    }

    record ManualLogView(long id, String command, String targetType, long targetId, String result, String fromStatus,
            String toStatus, String operator, String approver, String reason, Instant performedAt) {
    }
}
