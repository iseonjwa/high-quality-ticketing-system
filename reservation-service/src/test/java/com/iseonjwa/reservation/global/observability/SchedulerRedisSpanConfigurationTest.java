package com.iseonjwa.reservation.global.observability;

import brave.Tracing;
import brave.propagation.ThreadLocalCurrentTraceContext;
import io.lettuce.core.tracing.LettuceObservationContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.brave.bridge.BraveBaggageManager;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import io.micrometer.tracing.brave.bridge.CompositeSpanHandler;
import io.micrometer.tracing.exporter.FinishedSpan;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Brave 추적 처리기와 지표 처리기를 붙여, 예약 작업 바로 아래 Redis 명령 스팬만 Zipkin 보고에서 빠지고 지표는 남는지 본다.
 */
class SchedulerRedisSpanConfigurationTest {

    private final SchedulerRedisSpanConfiguration configuration = new SchedulerRedisSpanConfiguration();
    private final List<FinishedSpan> reported = new CopyOnWriteArrayList<>();
    private final MeterRegistry meters = new SimpleMeterRegistry();
    private Tracing tracing;
    private ObservationRegistry registry;

    @BeforeEach
    void setUp() {
        CompositeSpanHandler exporter = new CompositeSpanHandler(
                List.of(configuration.schedulerRedisSpanExclusion()), List.of(reported::add), List.of());
        tracing = Tracing.newBuilder()
                .currentTraceContext(ThreadLocalCurrentTraceContext.create())
                .addSpanHandler(exporter)
                .build();
        BraveTracer tracer = new BraveTracer(tracing.tracer(),
                new BraveCurrentTraceContext(tracing.currentTraceContext()), new BraveBaggageManager());

        registry = ObservationRegistry.create();
        registry.observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meters))
                .observationHandler(new DefaultTracingObservationHandler(tracer))
                .observationFilter(configuration.schedulerRedisSpanMarker());
    }

    @AfterEach
    void tearDown() {
        tracing.close();
    }

    @Test
    void 예약_작업_바로_아래_Redis_명령_스팬은_보고하지_않고_작업_스팬은_보고한다() throws Exception {
        Observation task = scheduledTask().start();
        redisCommand(task).start().stop();
        redisCommand(task).start().stop();
        task.stop();

        assertThat(reported).extracting(FinishedSpan::getName).containsExactly("task");
    }

    @Test
    void 요청_경로의_Redis_명령_스팬은_보고한다() {
        Observation request = Observation.start("http.server.requests", registry);
        redisCommand(request).start().stop();
        request.stop();

        assertThat(reported).extracting(FinishedSpan::getName).containsExactlyInAnyOrder("evalsha", "http.server.requests");
    }

    @Test
    void 예약_작업_아래_Redis_명령도_지표에는_남고_표시는_지표_태그에_들어가지_않는다() throws Exception {
        Observation task = scheduledTask().start();
        redisCommand(task).start().stop();
        task.stop();

        Timer timer = meters.find("lettuce").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.getId().getTag(SchedulerRedisSpanConfiguration.SCHEDULER_REDIS_MARK)).isNull();
    }

    @Test
    void 설정을_켜면_Redis_명령_스팬을_다시_보낸다() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(SchedulerRedisSpanConfiguration.class);

        runner.run(context -> assertThat(context).hasBean("schedulerRedisSpanMarker").hasBean("schedulerRedisSpanExclusion"));
        runner.withPropertyValues("observability.scheduler-redis-spans.export=true")
                .run(context -> assertThat(context).doesNotHaveBean("schedulerRedisSpanMarker").doesNotHaveBean("schedulerRedisSpanExclusion"));
    }

    private Observation scheduledTask() throws Exception {
        Method method = SampleScheduler.class.getDeclaredMethod("run");
        return Observation.createNotStarted("tasks.scheduled.execution",
                        () -> new ScheduledTaskObservationContext(new SampleScheduler(), method), registry)
                .contextualName("task");
    }

    private Observation redisCommand(Observation parent) {
        return Observation.createNotStarted("lettuce", () -> new LettuceObservationContext("redis"), registry)
                .contextualName("evalsha")
                .parentObservation(parent);
    }

    static class SampleScheduler {
        void run() {
        }
    }
}
