package com.iseonjwa.reservation.lottery.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 추첨 흐름의 지표 이름과 라벨을 한곳에 모으며, 라벨에는 ID 없이 값의 종류가 정해진 항목만 쓴다.
 */
@Component
public class LotteryMetrics {
    private static final Duration SETTLEMENT_MIN = Duration.ofSeconds(1);
    private static final Duration SETTLEMENT_MAX = Duration.ofHours(2);
    // 자동결제 완료 목표(numbers-and-measurement.md 1-2절)를 버킷 경계로 둬 10분 안 비율을 근사 없이 계산한다
    private static final Duration SETTLEMENT_TARGET = Duration.ofMinutes(10);
    private static final String OTHER_REASON = "OTHER";
    // 결제가 보내는 사유(contracts.md)와 추첨이 정한 사유로, 그 밖의 값은 라벨이 늘지 않게 OTHER로 묶는다
    private static final Set<String> KNOWN_FAILURE_REASONS = Set.of(
            "NOT_SENT",
            "TOKEN_EXPIRED",
            "CARD_DECLINED",
            "INSUFFICIENT_LIMIT",
            "BILLING_KEY_MISSING",
            "HOLD_EXPIRED",
            "NO_SEAT",
            "ASSIGN_ERROR",
            OTHER_REASON);

    public enum Job {
        ASSIGNMENT("assignment"),
        SUCCESSION("succession"),
        FINISH("finish");

        private final String label;

        Job(String label) {
            this.label = label;
        }
    }

    /**
     * 지표로 내보낼 진행 상태.
     *
     * @param unfinishedLotteries     종결되지 않은 추첨 수
     * @param oldestUnfinishedSeconds 추첨을 마치고 종결되지 않은 가장 오래된 추첨의 경과 시간(초)
     * @param unsettledWinners        결제 결과를 기다리는 당첨자 수
     * @param remainingLotterySeats   일반 판매로 돌아가지 않은 추첨석 수
     */
    public record Progress(long unfinishedLotteries,
                           long oldestUnfinishedSeconds,
                           long unsettledWinners,
                           long remainingLotterySeats) {}

    private final MeterRegistry registry;
    private final Clock clock;
    private final Counter assigned;
    private final Counter assignmentErrors;
    private final Counter assignmentRejected;
    private final Counter paymentRetries;
    private final Counter resultsNotApplied;
    private final Counter successionPromoted;
    private final Counter waitlistClosed;
    private final Counter seatsReverted;
    private final AtomicLong unfinishedLotteries;
    private final AtomicLong oldestUnfinishedSeconds;
    private final AtomicLong unsettledWinners;
    private final AtomicLong remainingLotterySeats;

    public LotteryMetrics(MeterRegistry registry, @Qualifier("commonAuditingClock") Clock clock) {
        this.registry = registry;
        this.clock = clock;
        this.assigned = assignmentCounter("assigned");
        this.assignmentErrors = assignmentCounter("error");
        this.assignmentRejected = assignmentCounter("rejected");
        this.paymentRetries = Counter.builder("lottery.payment.retries")
                .description("NOT_SENT로 같은 당첨자에게 다시 시도한 결제 수")
                .register(registry);
        this.resultsNotApplied = Counter.builder("lottery.result.not.applied")
                .description("결제 결과를 반영할 WON 응모를 찾지 못한 수")
                .register(registry);
        this.successionPromoted = Counter.builder("lottery.succession.promoted")
                .description("예비자에서 당첨으로 승계된 수")
                .register(registry);
        this.waitlistClosed = Counter.builder("lottery.waitlist.closed")
                .description("승계 마감으로 LOST가 된 예비자 수")
                .register(registry);
        this.seatsReverted = Counter.builder("lottery.finish.seats.reverted")
                .description("종결 때 원래 등급으로 되돌린 추첨석 수(판매된 좌석 포함)")
                .register(registry);
        this.unfinishedLotteries = registry.gauge("lottery.unfinished.count", new AtomicLong());
        this.oldestUnfinishedSeconds = registry.gauge("lottery.unfinished.oldest.seconds", new AtomicLong());
        this.unsettledWinners = registry.gauge("lottery.winners.unsettled", new AtomicLong());
        this.remainingLotterySeats = registry.gauge("lottery.seats.remaining", new AtomicLong());
        registerZeroSeries();
    }

    public void assigned() {
        assigned.increment();
    }

    public void assignmentError() {
        assignmentErrors.increment();
    }

    public void assignmentRejected(String reason) {
        assignmentRejected.increment();
        entryFailed(reason);
    }

    public void jobFailed(Job job) {
        jobFailures(job).increment();
    }

    public void succeeded(int promoted) {
        successionPromoted.increment(promoted);
    }

    public void waitlistClosed(int closed) {
        waitlistClosed.increment(closed);
    }

    public void seatsReverted(int reverted) {
        seatsReverted.increment(reverted);
    }

    public void updateProgress(Progress progress) {
        unfinishedLotteries.set(progress.unfinishedLotteries());
        oldestUnfinishedSeconds.set(progress.oldestUnfinishedSeconds());
        unsettledWinners.set(progress.unsettledWinners());
        remainingLotterySeats.set(progress.remainingLotterySeats());
    }

    public void paid(Instant drawAt) {
        afterCommit(() -> recordSettlement("paid", drawAt));
    }

    public void paymentFailed(Instant drawAt, String reason) {
        afterCommit(() -> {
            recordSettlement("failed", drawAt);
            entryFailed(reason);
        });
    }

    public void paymentRetried() {
        afterCommit(paymentRetries::increment);
    }

    public void resultNotApplied() {
        afterCommit(resultsNotApplied::increment);
    }

    // Note: 시계열이 첫 발생 때 생기면 increase()가 그 1건을 놓쳐 경보가 울리지 않으므로 라벨 값을 0으로 미리 등록한다.
    private void registerZeroSeries() {
        for (Job job : Job.values()) {
            jobFailures(job);
        }
        for (String reason : KNOWN_FAILURE_REASONS) {
            entryFailures(reason);
        }
        settlementTimer("paid");
        settlementTimer("failed");
    }

    private Counter assignmentCounter(String result) {
        return Counter.builder("lottery.assignment.results")
                .description("당첨 배정 시도의 결과별 수")
                .tag("result", result)
                .register(registry);
    }

    private Counter jobFailures(Job job) {
        return Counter.builder("lottery.job.failures")
                .description("추첨 스케줄러 작업의 실패 수")
                .tag("task", job.label)
                .register(registry);
    }

    private void entryFailed(String reason) {
        entryFailures(KNOWN_FAILURE_REASONS.contains(reason) ? reason : OTHER_REASON).increment();
    }

    private Counter entryFailures(String reason) {
        return Counter.builder("lottery.entry.failures")
                .description("당첨 응모가 FAILED로 끝난 사유별 수")
                .tag("reason", reason)
                .register(registry);
    }

    private void recordSettlement(String result, Instant drawAt) {
        settlementTimer(result).record(Duration.between(drawAt, clock.instant()));
    }

    private Timer settlementTimer(String result) {
        return Timer.builder("lottery.settlement.duration")
                .description("추첨 시각부터 당첨 응모의 결제 결과가 반영될 때까지의 시간")
                .tag("result", result)
                .publishPercentileHistogram()
                .serviceLevelObjectives(SETTLEMENT_TARGET)
                .minimumExpectedValue(SETTLEMENT_MIN)
                .maximumExpectedValue(SETTLEMENT_MAX)
                .register(registry);
    }

    // Note: 트랜잭션 안에서 바로 세면 롤백 뒤 Kafka 재처리 때 같은 결과가 두 번 집계되므로 커밋된 뒤에 기록한다.
    private static void afterCommit(Runnable record) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            record.run();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                record.run();
            }
        });
    }
}
