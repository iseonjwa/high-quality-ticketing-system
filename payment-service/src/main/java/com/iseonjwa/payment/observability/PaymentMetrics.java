package com.iseonjwa.payment.observability;

import com.iseonjwa.payment.pg.application.UnknownCause;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 결제 서비스의 지표 이름과 라벨을 한곳에 둔다(observability.md 1절, ADR 0024). 메서드는 라벨 값을 enum으로만 받아
 * 결제·사용자·예약 식별자가 라벨로 들어갈 길을 만들지 않는다. 예외는 등록된 고정 이름인 작업 이름과, 리스너 상수인 토픽이다.
 *
 * <p>Note: 값이 정해진 라벨 조합은 생성할 때 미리 등록한다. 사건이 한 번도 없어도 0인 시계열이 보여야 Prometheus의
 * {@code increase()}와 알림 규칙이 "처음 발생"을 놓치지 않는다. 서비스 구분은 이 클래스가 아니라 공통 설정의
 * {@code management.metrics.tags.application} 태그가 한다.
 */
@Component
public class PaymentMetrics {
    // Note: "job"은 Prometheus 수집 작업 라벨과 겹쳐 exported_job으로 바뀌고 job은 수집 작업 이름(spring)이 된다(ISE-195)
    private static final String TASK_TAG = "task";
    private static final Duration APPROVE_MIN = Duration.ofMillis(1);
    private static final Duration APPROVE_MAX = Duration.ofSeconds(30);

    private final MeterRegistry registry;
    private final Map<ApproveOutcome, Timer> approve = new EnumMap<>(ApproveOutcome.class);
    private final Map<PgOperation, Counter> amountMismatch = new EnumMap<>(PgOperation.class);
    private final Map<PgOperation, Map<UnknownCause, Counter>> pgUnknown = new EnumMap<>(PgOperation.class);
    private final Map<TransitionOperation, Counter> transitionConflict = new EnumMap<>(TransitionOperation.class);
    private final Map<String, Counter> dlqHandlerFailures = new ConcurrentHashMap<>();
    // 게이지는 약한 참조로 값을 읽으므로 값 객체를 여기서 붙들고 있어야 한다
    private final Map<String, AtomicLong> jobLastSuccess = new ConcurrentHashMap<>();
    private final Map<String, Counter> jobFailures = new ConcurrentHashMap<>();
    private final Map<String, AtomicReference<Double>> warmupFirst = new ConcurrentHashMap<>();
    private final AtomicReference<Double> warmupTotal = new AtomicReference<>(0d);
    private volatile boolean warmupTotalRegistered;

    public PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (var outcome : ApproveOutcome.values()) {
            approve.put(outcome, Timer.builder("payment.approve")
                    .description("승인 API 한 호출의 처리 시간. 결과별 건수는 이 타이머의 count다")
                    .tag("outcome", label(outcome))
                    .publishPercentileHistogram()
                    .minimumExpectedValue(APPROVE_MIN)
                    .maximumExpectedValue(APPROVE_MAX)
                    .register(registry));
        }
        for (var operation : PgOperation.values()) {
            amountMismatch.put(operation, Counter.builder("payment.pg.amount.mismatch")
                    .description("PG가 응답한 금액·통화가 결제나 환불과 달라 운영자 확인으로 넘긴 수")
                    .tag("operation", label(operation))
                    .register(registry));
            var byCause = new EnumMap<UnknownCause, Counter>(UnknownCause.class);
            for (var cause : UnknownCause.values()) {
                byCause.put(cause, Counter.builder("payment.pg.unknown")
                        .description("PG 결과를 알 수 없었던 호출 수")
                        .tag("operation", label(operation))
                        .tag("cause", label(cause))
                        .register(registry));
            }
            pgUnknown.put(operation, byCause);
        }
        for (var operation : TransitionOperation.values()) {
            transitionConflict.put(operation, Counter.builder("payment.transition.conflict")
                    .description("조건부 UPDATE가 0행이 된 수. 다른 경로가 먼저 상태를 바꿨다")
                    .tag("operation", label(operation))
                    .register(registry));
        }
    }

    /** 승인 API 호출 하나를 기록한다. 호출마다 정확히 한 번 부른다. */
    public void recordApprove(ApproveOutcome outcome, long elapsedNanos) {
        approve.get(outcome).record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    public void recordPgCall(PgOperation operation, PgCallOutcome outcome, long elapsedNanos) {
        Timer.builder("payment.pg.call")
                .description("PG 호출 시간. 어댑터가 예외를 던진 호출은 outcome=exception이다")
                .tag("operation", label(operation))
                .tag("outcome", label(outcome))
                .register(registry)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    public void pgUnknown(PgOperation operation, UnknownCause cause) {
        pgUnknown.get(operation).get(cause).increment();
    }

    public void pgAmountMismatch(PgOperation operation) {
        amountMismatch.get(operation).increment();
    }

    public void transitionConflict(TransitionOperation operation) {
        transitionConflict.get(operation).increment();
    }

    /** DLQ 처리기가 예외로 끝났다. 토픽은 리스너가 가진 상수다. */
    public void dlqHandlerFailure(String topic) {
        dlqHandlerFailureCounter(topic).increment();
    }

    /** 리스너가 만들어질 때 부른다. 실패가 없어도 0인 시계열이 보이게 한다. */
    public void trackDlqHandler(String topic) {
        dlqHandlerFailureCounter(topic);
    }

    private Counter dlqHandlerFailureCounter(String topic) {
        return dlqHandlerFailures.computeIfAbsent(topic, key -> Counter.builder("payment.dlq.handler.failures")
                .description("DLQ 처리기가 처리 중 예외로 끝난 수")
                .tag("topic", key)
                .register(registry));
    }

    /** 기동 웜업 단계의 첫 호출 시간을 기동 때 한 번 남긴다(ISE-243). 값은 바뀌지 않는다. */
    public void warmupFirstCall(String step, double seconds) {
        var value = warmupFirst.computeIfAbsent(step, key -> {
            var holder = new AtomicReference<Double>(seconds);
            Gauge.builder("payment.warmup.first.call", holder, AtomicReference::get)
                    .description("기동 웜업 단계의 첫 호출 시간")
                    .baseUnit("seconds")
                    .tag("step", key)
                    .register(registry);
            return holder;
        });
        value.set(seconds);
    }

    /** 기동 웜업 전체 시간을 기동 때 한 번 남긴다(ISE-243). */
    public void warmupTotal(double seconds) {
        warmupTotal.set(seconds);
        if (!warmupTotalRegistered) {
            Gauge.builder("payment.warmup", warmupTotal, AtomicReference::get)
                    .description("기동 웜업 전체 시간")
                    .baseUnit("seconds")
                    .register(registry);
            warmupTotalRegistered = true;
        }
    }

    /**
     * 배경 작업을 등록한다. 마지막 성공 시각은 {@code startedAt}으로 시작한다. {@code job} 값은 등록된 고정 이름이다.
     */
    public void trackJob(String job, Instant startedAt) {
        jobLastSuccess.computeIfAbsent(job, key -> {
            var value = new AtomicLong(startedAt.getEpochSecond());
            Gauge.builder("payment.job.last.success.timestamp", value, AtomicLong::get)
                    .description("작업 주기가 예외 없이 끝난 마지막 시각(epoch 초)")
                    .tag(TASK_TAG, key)
                    .register(registry);
            return value;
        });
        jobFailureCounter(job);
    }

    public void jobSucceeded(String job, Instant at) {
        var value = jobLastSuccess.get(job);
        if (value != null) value.set(at.getEpochSecond());
    }

    public void jobFailed(String job) {
        jobFailureCounter(job).increment();
    }

    private Counter jobFailureCounter(String job) {
        return jobFailures.computeIfAbsent(job, key -> Counter.builder("payment.job.failures")
                .description("작업 주기가 예외로 끝난 수")
                .tag(TASK_TAG, key)
                .register(registry));
    }

    static String label(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
