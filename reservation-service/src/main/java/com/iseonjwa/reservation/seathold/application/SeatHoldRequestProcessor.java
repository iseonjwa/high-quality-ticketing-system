package com.iseonjwa.reservation.seathold.application;

import com.iseonjwa.common.messaging.inbox.ProcessedEventService;
import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.reservation.seathold.domain.repository.SeatHoldInventoryRepository;
import com.iseonjwa.reservation.seathold.domain.repository.SeatHoldRequestRepository;
import com.iseonjwa.reservation.seathold.observability.SeatHoldConsumerMetrics;
import com.iseonjwa.reservation.seathold.observability.SeatHoldConsumerMetrics.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 접수형 선점의 실제 선점(ISE-17, SA 8-3·10-4). {@code seat-hold.request} 메시지 한 건을 받아 요청을 {@code HELD}나
 * {@code REJECTED(사유)}로 닫는다. 선점 본체는 동기형과 같은 {@link SeatHolding}이다.
 *
 * <p>Note: 처리 시작 때 요청 행을 잠그고 30초 시한을 확인한다. 시한 정리 작업과 같은 행을 두고 순서가 정해져,
 * 좌석은 잡혔는데 요청은 TIMEOUT인 상태가 생기지 않는다. 시한이 지난 메시지는 좌석을 잠그지 않고 쿼리 하나로 끝낸다.
 * 오픈 피크에 줄이 밀려 늦은 메시지가 많아질 때 커넥션과 좌석 잠금을 아끼려는 선택이다
 * (docs/learning/kwonwoo/qna/2026-11-13-ise17-consumer-race-and-lock-timeout-tradeoffs.md).
 */
@Service
public class SeatHoldRequestProcessor {
    public static final String CONSUMER_GROUP = "reservation.seat-hold";
    // 접수부터 이 시간 안에 처리를 시작하지 못한 요청은 TIMEOUT으로 닫는다(SA 10-5). 시작한 요청은 끝까지 처리한다
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    static final String SEAT_ALREADY_HELD = "SEAT_ALREADY_HELD";
    static final String PURCHASE_LIMIT_EXCEEDED = "PURCHASE_LIMIT_EXCEEDED";
    static final String PROCESSING_FAILED = "PROCESSING_FAILED";
    static final String TIMEOUT = "TIMEOUT";

    private static final Logger log = LoggerFactory.getLogger(SeatHoldRequestProcessor.class);

    private final ProcessedEventService processedEvents;
    private final SeatHoldRequestRepository requests;
    private final SeatHoldInventoryRepository inventory;
    private final SeatHolding seatHolding;
    private final ConsumedEntryTokens consumedEntryTokens;
    private final SeatHoldConsumerMetrics metrics;
    private final Clock clock;
    private final Duration lockTimeout;

    /**
     * @param lockTimeout 좌석·매수 행 잠금 대기 시한. 동기형(500ms)보다 짧다. 같은 구역 메시지는 한 줄로 처리되므로
     *                    기다린다는 것은 줄 밖의 작업(만료, 취소, 다른 구역 키의 섞인 요청)과 부딪친 것이고, 기다리는 동안
     *                    그 줄의 뒤 메시지가 모두 멈춘다
     */
    public SeatHoldRequestProcessor(ProcessedEventService processedEvents, SeatHoldRequestRepository requests,
            SeatHoldInventoryRepository inventory, SeatHolding seatHolding, ConsumedEntryTokens consumedEntryTokens,
            SeatHoldConsumerMetrics metrics, @Qualifier("commonAuditingClock") Clock clock,
            @Value("${seat-hold.consumer.lock-timeout:100ms}") Duration lockTimeout) {
        this.processedEvents = processedEvents;
        this.requests = requests;
        this.inventory = inventory;
        this.seatHolding = seatHolding;
        this.consumedEntryTokens = consumedEntryTokens;
        this.metrics = metrics;
        this.clock = clock;
        this.lockTimeout = lockTimeout;
    }

    /**
     * 메시지 한 건을 처리한다. 같은 이벤트를 두 번 받아도 한 번만 처리한다.
     *
     * <p>Note: 업무 거절(좌석 충돌, 매수 초과)은 선점 트랜잭션을 롤백해 좌석·매수 변경을 되돌린 뒤, 새 트랜잭션에서 거절과
     * 처리 기록을 쓴다. 두 트랜잭션 사이에 멈춰 다시 전달돼도 요청이 아직 REQUESTED라 같은 판단을 다시 한다.
     * 그 밖의 예외는 그대로 던져 Kafka 재시도와 DLQ로 보낸다.
     */
    public void process(SeatHoldRequestedEvent event) {
        var outcome = new AtomicReference<Outcome>();
        try {
            processedEvents.processOnce(event.eventId(), CONSUMER_GROUP, () -> outcome.set(holdIfOpen(event)));
        } catch (BusinessException rejected) {
            var reason = reasonOf(rejected);
            var closed = new AtomicBoolean();
            processedEvents.processOnce(event.eventId(), CONSUMER_GROUP,
                    () -> closed.set(requests.markRejected(event.requestId(), reason, clock.instant()) == 1));
            if (closed.get()) {
                metrics.record(outcomeOf(rejected, reason));
            }
            return;
        }
        if (outcome.get() == null) {
            // 이미 처리한 이벤트다
            return;
        }
        metrics.record(outcome.get());
        if (outcome.get() == Outcome.HELD) {
            // Note: 선점 트랜잭션이 끝나 커넥션을 돌려준 뒤 대기열 issued를 정리한다(ADR 0053, ISE-207). 실패해도 결과는 그대로다
            consumedEntryTokens.discard(event.scheduleId(), event.userId(), event.entryTokenJti());
        }
    }

    /** DLQ로 간 요청을 닫는다. 이미 닫혔으면 아무것도 하지 않는다(E15). */
    @Transactional
    public void rejectAsFailed(UUID requestId) {
        if (requests.markRejected(requestId, PROCESSING_FAILED, clock.instant()) == 1) {
            metrics.record(Outcome.FAILED);
        }
    }

    /** 접수 뒤 30초가 지나도록 처리를 시작하지 못한 요청을 오래된 순으로 limit건까지 닫고, 닫은 수를 돌려준다. */
    @Transactional
    public int rejectTimedOut(int limit) {
        var now = clock.instant();
        return requests.rejectTimedOut(now.minus(REQUEST_TIMEOUT), now, limit);
    }

    private Outcome holdIfOpen(SeatHoldRequestedEvent event) {
        var createdAt = requests.lockRequestedCreatedAt(event.requestId());
        if (createdAt.isEmpty()) {
            return Outcome.SKIPPED;
        }
        // Note: created_at은 앱 시계(commonAuditingClock)로 쓴 값이라 비교도 앱 시계로 한다. DB now()와 섞으면
        // 앱과 DB의 시계 차이만큼 시한이 어긋난다. 시한 정리 작업도 같은 시계를 쓴다
        Instant now = clock.instant();
        metrics.recordDelay(Duration.between(createdAt.get(), now));
        if (!createdAt.get().isAfter(now.minus(REQUEST_TIMEOUT))) {
            requests.markRejected(event.requestId(), TIMEOUT, now);
            return Outcome.LATE;
        }
        var schedule = inventory.findSchedule(event.scheduleId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        var seats = inventory.findSeats(schedule.getId(), event.seatIds());
        if (seats.size() != event.seatIds().size()) {
            throw new BusinessException(SeatHoldErrorCode.SEAT_COUNT_MISMATCH);
        }
        inventory.applyLockTimeout(lockTimeout);
        var reservation = seatHolding.hold(event.requestId(), event.userId(), schedule, seats, event.seatIds(), now);
        if (requests.markHeld(event.requestId(), reservation.getId(), now, event.userId()) != 1) {
            // 요청 행을 잠그고 있으므로 일어나면 안 된다. 예외로 좌석·예매까지 되돌린다
            throw new IllegalStateException("잠근 선점 요청을 HELD로 바꾸지 못했습니다: " + event.requestId());
        }
        return Outcome.HELD;
    }

    // 조회 API의 reasonCode는 네 값뿐이다(contracts.md 2-2). 좌석·매수 거절이 아니면 처리 실패로 알린다
    private static String reasonOf(BusinessException rejected) {
        if (rejected.getErrorCode() == SeatHoldErrorCode.SEAT_ALREADY_HELD) {
            return SEAT_ALREADY_HELD;
        }
        if (rejected.getErrorCode() == SeatHoldErrorCode.PURCHASE_LIMIT_EXCEEDED) {
            return PURCHASE_LIMIT_EXCEEDED;
        }
        log.warn("접수형 선점을 처리할 수 없어 PROCESSING_FAILED로 닫습니다. code={}", rejected.getErrorCode().code());
        return PROCESSING_FAILED;
    }

    private static Outcome outcomeOf(BusinessException rejected, String reason) {
        if (rejected instanceof SeatLockTimeoutException) {
            return Outcome.LOCK_TIMEOUT;
        }
        return switch (reason) {
            case SEAT_ALREADY_HELD -> Outcome.SEAT_CONFLICT;
            case PURCHASE_LIMIT_EXCEEDED -> Outcome.LIMIT_EXCEEDED;
            default -> Outcome.FAILED;
        };
    }
}
