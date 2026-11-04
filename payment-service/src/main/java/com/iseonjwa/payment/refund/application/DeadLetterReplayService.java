package com.iseonjwa.payment.refund.application;

import com.iseonjwa.common.messaging.consumer.NonRetryableMessageException;
import com.iseonjwa.payment.payment.domain.StatusActor;
import com.iseonjwa.payment.refund.domain.DeadLetter;
import com.iseonjwa.payment.refund.domain.DeadLetterRepository;
import com.iseonjwa.payment.refund.domain.DeadLetterResolution;
import com.iseonjwa.payment.refund.domain.RefundSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;

/**
 * {@code OPEN}인 {@code dead_letters}의 원문({@code payload_raw})을 DLQ 처리기와 같은 파서·검증·생성 경로로 다시 처리한다
 * (Ops {@code dead-letter resolve --replay}). 파싱은 {@link RefundRequestedParser}, 검증과 환불 생성은
 * {@link RefundRequestService#settle}을 DLQ 처리기와 함께 쓴다.
 *
 * <p>Note: DLQ 처리기와 달리 {@code dead_letters} 행을 새로 만들지 않고 {@code processed_events}도 쓰지 않는다. 행과
 * 처리 기록은 처음 DLQ로 왔을 때 이미 만들어졌다. 내용 문제로는 아무것도 쓰지 않고 {@link ReplayResult.Rejected}로
 * 돌려주며, 호출한 쪽이 거부로 알린다.
 */
@Service
public class DeadLetterReplayService {
    private static final String UNPARSABLE = "역직렬화·봉투 오류: ";

    private final RefundRequestedParser parser;
    private final RefundRequestService refundRequests;
    private final DeadLetterRepository deadLetters;
    private final Clock clock;

    public DeadLetterReplayService(RefundRequestedParser parser, RefundRequestService refundRequests,
            DeadLetterRepository deadLetters, @Qualifier("commonAuditingClock") Clock clock) {
        this.parser = parser;
        this.refundRequests = refundRequests;
        this.deadLetters = deadLetters;
        this.clock = clock;
    }

    /** 원문을 읽고 결제와 대조한다. 읽기만 하고 아무것도 쓰지 않는다. */
    public ReplayCheck check(String payloadRaw) {
        RefundRequestedMessage message;
        try {
            message = parser.parse(payloadRaw);
        } catch (NonRetryableMessageException unparsable) {
            return new ReplayCheck.Unreplayable(UNPARSABLE + unparsable.getMessage());
        }
        return switch (refundRequests.verify(message.payload())) {
            case RefundRequestVerification.Invalid invalid -> new ReplayCheck.Unreplayable(invalid.message());
            case RefundRequestVerification.Valid valid ->
                    new ReplayCheck.Verified(message.eventId(), message.payload(), valid.reason().name());
        };
    }

    /**
     * 환불을 만들거나 이미 있는 환불에 연결하고 {@code dead_letters}를 {@code RESOLVED}로 닫는다. 호출한 쪽의 트랜잭션에
     * 참여한다. {@link ReplayResult.Rejected}나 {@link ReplayResult.NotOpen}이면 호출한 쪽이 트랜잭션을 롤백해야 한다
     * ({@code NotOpen}은 환불을 이미 만든 뒤에 알게 된다).
     */
    public ReplayResult replay(DeadLetter deadLetter, StatusActor actor) {
        RefundRequestedMessage message;
        try {
            message = parser.parse(deadLetter.payloadRaw());
        } catch (NonRetryableMessageException unparsable) {
            return new ReplayResult.Rejected(UNPARSABLE + unparsable.getMessage());
        }
        var now = clock.instant();
        return switch (refundRequests.settle(message.payload(), RefundSource.DLQ, message.eventId(), actor)) {
            case RefundRequestOutcome.Rejected rejected -> new ReplayResult.Rejected(rejected.message());
            case RefundRequestOutcome.Created created -> close(deadLetter, DeadLetterResolution.REPLAYED, created.refundId(), now);
            case RefundRequestOutcome.Duplicate duplicate ->
                    close(deadLetter, DeadLetterResolution.DUPLICATE, duplicate.refundId(), now);
        };
    }

    // Note: 조건부 UPDATE(status = 'OPEN')가 상태 검사를 겸한다. 계획을 만든 뒤 다른 경로가 먼저 닫았으면 0행이다
    private ReplayResult close(DeadLetter deadLetter, DeadLetterResolution resolution, Long refundId,
            java.time.Instant now) {
        if (!deadLetters.resolve(deadLetter.id(), resolution, refundId, now)) return new ReplayResult.NotOpen();
        return new ReplayResult.Closed(resolution, refundId);
    }

    /** {@link #check}의 결과. */
    public sealed interface ReplayCheck {
        /** 원문을 읽었고 결제와 맞다. {@code reason}은 환불 사유 이름이다. */
        record Verified(UUID eventId, RefundRequestedPayload payload, String reason) implements ReplayCheck {}

        /** 읽지 못했거나 결제와 맞지 않는다. {@code message}는 운영자가 읽을 사유다. */
        record Unreplayable(String message) implements ReplayCheck {}
    }

    /** {@link #replay}의 결과. */
    public sealed interface ReplayResult {
        /** {@code RESOLVED}로 닫았다. {@code resolution}은 {@code REPLAYED}(환불 생성)나 {@code DUPLICATE}(이미 있음)다. */
        record Closed(DeadLetterResolution resolution, Long refundId) implements ReplayResult {}

        record Rejected(String message) implements ReplayResult {}

        /** 이미 {@code OPEN}이 아니다. */
        record NotOpen() implements ReplayResult {}
    }
}
