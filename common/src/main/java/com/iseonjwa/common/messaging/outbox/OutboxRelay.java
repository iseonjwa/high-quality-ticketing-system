package com.iseonjwa.common.messaging.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Outbox 릴레이 한 번의 실행(events-and-reliability.md 3절). 집어서 임대하고, 브로커 확인을 받은 행만 SENT로
 * 바꾼다. 확인 전에 죽으면 임대가 끝난 뒤 다시 집혀 중복 전송되고 소비자가 멱등으로 막는다.
 *
 * <p>Note: 이 클래스는 트랜잭션을 열지 않는다. 브로커 전송을 DB 트랜잭션 안에서 기다리면 Kafka 지연 동안 행 잠금과
 * DB 연결을 붙잡는다. 집기와 SENT 기록은 각각 한 문장짜리 쓰기다. 전송 대기는 {@code sendTimeout}까지이고
 * (프로듀서 max.block.ms·delivery.timeout.ms가 이 안에 들어와야 한다, 기동 때 검증), 마감이 지나면 아직 보내지 않은
 * 행의 임대를 풀어 다음 실행이 집게 한다. 이렇게 해야 임대(≥ 시한의 2배) 안에서 전송이 끝나 다른 릴레이가 같은 행을
 * 보내는 일이 없다. 한 실행에서 같은 키의 행은 하나뿐이라 한꺼번에 보내도
 * 순서가 유지된다. SENT·실패 기록에서 DB 예외가 나면 실행이 중단된다. DB 장애 중에 계속 보내면 중복만 늘기 때문이다.
 *
 * <p>실패는 둘로 나눈다. 일시 오류는 지수 백오프({@link OutboxBackoff})로 다음 시도 시각을 미루며 계속 재시도하고,
 * 영구 오류({@link OutboxFailureClassifier})만 FAILED로 바꿔 멈춘다.
 *
 * <p>Note: FAILED로 바뀐 이벤트 수는 {@code outbox.events.failed}가 센다.
 */
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int ERROR_MAX_LENGTH = 500;

    public record Result(int claimed, int sent) {}

    private final OutboxRepository repository;
    private final OutboxEventSender sender;
    private final Clock clock;
    private final OutboxProperties properties;
    private final OutboxBackoff backoff;
    private final OutboxMetrics metrics;

    public OutboxRelay(OutboxRepository repository, OutboxEventSender sender, Clock clock,
            OutboxProperties properties) {
        this(repository, sender, clock, properties, OutboxBackoff.withRandomJitter());
    }

    public OutboxRelay(OutboxRepository repository, OutboxEventSender sender, Clock clock,
            OutboxProperties properties, OutboxBackoff backoff) {
        this(repository, sender, clock, properties, backoff, OutboxMetrics.disabled());
    }

    public OutboxRelay(OutboxRepository repository, OutboxEventSender sender, Clock clock,
            OutboxProperties properties, OutboxBackoff backoff, OutboxMetrics metrics) {
        this.repository = repository;
        this.sender = sender;
        this.clock = clock;
        this.properties = properties;
        this.backoff = backoff;
        this.metrics = metrics;
    }

    public int batchSize() {
        return properties.batchSize();
    }

    public Result relayOnce() {
        var now = clock.instant();
        var leaseUntil = now.plus(properties.lease());
        var claimed = repository.claimNext(now, leaseUntil, properties.batchSize());
        if (claimed.isEmpty()) return new Result(0, 0);

        var deadline = System.nanoTime() + properties.sendTimeout().toNanos();
        var inFlight = new LinkedHashMap<OutboxMessage, CompletableFuture<Void>>();
        var notStarted = new ArrayList<Long>();
        try {
            for (var message : claimed) {
                // 마감이 지났거나 종료 중이면 더 시작하지 않는다
                if (System.nanoTime() - deadline >= 0 || Thread.currentThread().isInterrupted()) {
                    notStarted.add(message.id());
                    continue;
                }
                try {
                    inFlight.put(message, sender.send(message.topic(), message.messageKey(), message.payload()));
                } catch (RuntimeException exception) {
                    fail(message, exception);
                }
            }
            return new Result(claimed.size(), confirm(inFlight, deadline));
        } finally {
            // 임대를 푸는 쓰기에서 예외가 나도 이미 브로커 확인을 받은 행의 SENT 기록은 앞에서 끝나 있다
            repository.release(notStarted, leaseUntil);
        }
    }

    // 브로커 확인을 마감까지 기다려 확인된 행만 SENT로 바꾸고, 바꾼 건수를 돌려준다.
    private int confirm(LinkedHashMap<OutboxMessage, CompletableFuture<Void>> inFlight, long deadline) {
        var sent = 0;
        for (var entry : inFlight.entrySet()) {
            // 종료 중이면 남은 행은 기록 없이 둔다. 임대가 끝나면 다시 집힌다(중복 가능, 소비자가 멱등)
            if (Thread.currentThread().isInterrupted()) break;
            if (await(entry.getKey(), entry.getValue(), deadline) && repository.markSent(entry.getKey().id(), clock.instant())) {
                sent++;
            }
        }
        return sent;
    }

    // 확인하면 true, 실패·시한 초과면 실패를 기록하고 false다.
    private boolean await(OutboxMessage message, CompletableFuture<Void> future, long deadline) {
        try {
            future.get(Math.max(deadline - System.nanoTime(), 0), TimeUnit.NANOSECONDS);
            return true;
        } catch (ExecutionException exception) {
            fail(message, exception.getCause());
        } catch (TimeoutException exception) {
            // Note: 시한 안에 확인을 못 받았을 뿐 전송이 취소되는 것은 아니다. 프로듀서가 계속 보내 브로커에 닿을 수
            // 있고, 그 경우 임대가 끝난 뒤 다시 보내져 중복된다(소비자가 멱등). 그래서 취소 호출을 하지 않는다
            fail(message, new IllegalStateException("브로커 확인 시한(" + properties.sendTimeout().toMillis() + "ms)을 넘겼다"));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    private void fail(OutboxMessage message, Throwable cause) {
        var root = rootCause(cause);
        var description = truncate(root.getMessage() != null ? root.getMessage() : root.getClass().getName());
        if (OutboxFailureClassifier.isPermanent(cause)) {
            if (repository.markFailed(message.id(), description)) {
                // 값 원문(페이로드, 예외 메시지)은 남기지 않는다. 예외 종류만 적고 상세는 last_error 컬럼에 있다
                log.error("event=OUTBOX_FAILED eventId={} topic={} messageKey={} cause={}", message.eventId(),
                        message.topic(), message.messageKey(), root.getClass().getName());
                // FAILED 건수 알림은 outbox.failed.count Gauge가 맡는다(TODO(ISE-157): 알림 규칙)
                metrics.eventFailed();
            }
            return;
        }
        log.warn("Outbox 발행 실패 eventId={} topic={}", message.eventId(), message.topic(), cause);
        var failures = Math.min(message.attempts() + 1, OutboxRepository.ATTEMPTS_MAX);
        repository.recordFailure(message.id(), description, clock.instant().plus(backoff.delay(failures)));
    }

    // Spring Kafka는 원인을 "Failed to send"로 감싼다. 기록에는 실제 원인을 남긴다
    private static Throwable rootCause(Throwable throwable) {
        var root = throwable;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root;
    }

    private static String truncate(String message) {
        return message.length() <= ERROR_MAX_LENGTH ? message : message.substring(0, ERROR_MAX_LENGTH);
    }
}
