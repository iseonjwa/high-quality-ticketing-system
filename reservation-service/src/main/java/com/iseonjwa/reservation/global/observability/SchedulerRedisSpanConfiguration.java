package com.iseonjwa.reservation.global.observability;

import io.lettuce.core.tracing.LettuceObservationContext;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import io.micrometer.observation.ObservationView;
import io.micrometer.tracing.exporter.FinishedSpan;
import io.micrometer.tracing.exporter.SpanExportingPredicate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

/**
 * 예약 작업(@Scheduled) 바로 아래에서 부른 Redis 명령 스팬을 Zipkin으로 보내지 않는다.
 * 대기열 입장 스케줄러가 0.5초마다 evalsha 19개를 부르며 평시 스팬의 약 87%를 만들어 Zipkin 메모리 저장 한도를 40분 만에 채웠다(TS-54).
 * 예약 작업 스팬과 요청 경로의 Redis 스팬은 그대로 보낸다. 다시 보려면 {@code observability.scheduler-redis-spans.export=true}로 켠다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "observability.scheduler-redis-spans.export", havingValue = "false", matchIfMissing = true)
class SchedulerRedisSpanConfiguration {

    static final String SCHEDULER_REDIS_MARK = "iseonjwa.scheduler-redis";

    // Note: 관측(Observation)은 끄지 않고 스팬만 버린다. 관측을 끄면 같은 관측에서 나오는 lettuce_* 지표도 사라진다.
    // 부모 관측은 Lettuce가 관측을 만든 뒤에 붙이므로 ObservationPredicate(생성 시점)로는 부모를 볼 수 없다.
    // 그래서 부모가 붙은 뒤인 종료 시점 필터에서 표시를 달고, 내보내기 조건에서 그 표시가 있는 스팬을 거른다.
    // 표시는 높은 카디널리티 값이라 지표 태그에는 들어가지 않는다.
    @Bean
    ObservationFilter schedulerRedisSpanMarker() {
        return context -> {
            if (isRedisCommandUnderScheduledTask(context)) {
                context.addHighCardinalityKeyValue(KeyValue.of(SCHEDULER_REDIS_MARK, "true"));
            }
            return context;
        };
    }

    @Bean
    SpanExportingPredicate schedulerRedisSpanExclusion() {
        return SchedulerRedisSpanConfiguration::isExportable;
    }

    static boolean isExportable(FinishedSpan span) {
        return !span.getTags().containsKey(SCHEDULER_REDIS_MARK);
    }

    private static boolean isRedisCommandUnderScheduledTask(Observation.Context context) {
        if (!(context instanceof LettuceObservationContext)) {
            return false;
        }
        ObservationView parent = context.getParentObservation();
        return parent != null && parent.getContextView() instanceof ScheduledTaskObservationContext;
    }
}
