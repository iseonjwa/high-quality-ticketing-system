package com.iseonjwa.payment.event.application;

import com.iseonjwa.payment.event.domain.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;

/**
 * Outbox 릴레이 한 번의 실행(events-and-reliability.md 3절). 집어서 임대하고, 트랜잭션 밖에서 브로커 확인을
 * 받은 뒤 SENT로 바꾼다. 확인 전에 죽으면 임대가 끝난 뒤 다시 집혀 중복 전송되고 소비자가 멱등으로 막는다.
 *
 * <p>Note: 이 클래스는 트랜잭션을 열지 않는다. 브로커 전송을 DB 트랜잭션 안에서 기다리면 Kafka 지연 동안
 * 행 잠금과 DB 연결을 붙잡는다. 집기와 SENT 기록은 저장소 메서드가 각자 짧은 트랜잭션으로 한다.
 * 지수 백오프·FAILED·정리는 ISE-40에서 더한다. 지금은 실패하면 임대가 끝난 뒤 다시 시도한다.
 */
@Service
public class OutboxRelayService {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);
    private static final int ERROR_MAX_LENGTH = 500;

    private final OutboxRepository outbox;
    private final OutboxEventSender sender;
    private final Clock clock;
    private final int batchSize;
    private final Duration lease;

    public OutboxRelayService(OutboxRepository outbox, OutboxEventSender sender,
            @Qualifier("commonAuditingClock") Clock clock, OutboxRelayProperties properties) {
        this.outbox = outbox;
        this.sender = sender;
        this.clock = clock;
        this.batchSize = properties.batchSize();
        this.lease = properties.lease();
    }

    /** 이번 실행에서 SENT로 바꾼 건수를 돌려준다. */
    public int relayOnce() {
        var now = clock.instant();
        var claimed = outbox.claimNext(now, now.plus(lease), batchSize);
        var sent = 0;
        for (var event : claimed) {
            try {
                sender.send(event.getTopic(), event.getMessageKey(), event.getPayload());
            } catch (RuntimeException exception) {
                log.warn("Outbox 발행 실패 eventId={} topic={}", event.getEventId(), event.getTopic(), exception);
                outbox.recordFailure(event.getId(), truncate(exception.getMessage()));
                continue;
            }
            if (outbox.markSent(event.getId(), clock.instant())) sent++;
        }
        return sent;
    }

    private static String truncate(String message) {
        if (message == null) return "unknown";
        return message.length() <= ERROR_MAX_LENGTH ? message : message.substring(0, ERROR_MAX_LENGTH);
    }
}
