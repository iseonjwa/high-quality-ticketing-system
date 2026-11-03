package com.iseonjwa.common.messaging;

import com.iseonjwa.common.messaging.inbox.ProcessedEventRepository;
import com.iseonjwa.common.messaging.inbox.ProcessedEventService;
import com.iseonjwa.common.messaging.outbox.KafkaOutboxEventSender;
import com.iseonjwa.common.messaging.outbox.OutboxBackoff;
import com.iseonjwa.common.messaging.outbox.OutboxEventSender;
import com.iseonjwa.common.messaging.outbox.OutboxMetrics;
import com.iseonjwa.common.messaging.outbox.OutboxProperties;
import com.iseonjwa.common.messaging.outbox.OutboxRelay;
import com.iseonjwa.common.messaging.outbox.OutboxRelayScheduler;
import com.iseonjwa.common.messaging.outbox.OutboxRepository;
import com.iseonjwa.common.messaging.outbox.OutboxWriter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;

/**
 * Outbox 발행과 처리 이력(processed_events)을 쓰는 서비스가 {@code @Import}한다. 스캔하지 않으므로 User·Gateway 같은
 * Kafka 없는 서비스에는 빈이 생기지 않는다(ADR 0013).
 *
 * <p>쓰는 서비스는 spring-jdbc와 spring-kafka를 직접 선언하고, Flyway로 {@code outbox_events}와 {@code processed_events}
 * 테이블을 만든다. 시계는 {@code commonAuditingClock}을 쓰므로 {@code CommonJpaConfiguration}도 함께 가져와야 한다.
 * {@code messaging.outbox.relay-enabled=false}면 릴레이 실행기를 만들지 않는다(테스트). 릴레이는 {@code @Scheduled}를 쓰지
 * 않고 전용 실행기를 직접 소유하므로 이 설정은 {@code @EnableScheduling}을 켜지 않는다.
 * 프로듀서의 {@code max.block.ms + delivery.timeout.ms}가 {@code send-timeout} 안에 들어오지 않으면 기동이 실패한다.
 */
@Configuration(proxyBeanMethods = false)
public class CommonOutboxConfiguration {
    // 전송 시한을 넘긴 뒤 SENT·실패 기록 쓰기가 끝나길 기다리는 여유
    private static final Duration STOP_MARGIN = Duration.ofSeconds(5);

    @Bean
    OutboxProperties outboxProperties(@Value("${messaging.outbox.batch-size:20}") int batchSize,
            @Value("${messaging.outbox.lease:30s}") Duration lease,
            @Value("${messaging.outbox.send-timeout:12s}") Duration sendTimeout) {
        return new OutboxProperties(batchSize, lease, sendTimeout);
    }

    @Bean
    OutboxRepository outboxRepository(JdbcTemplate jdbc) {
        return new OutboxRepository(jdbc);
    }

    @Bean
    OutboxWriter outboxWriter(OutboxRepository repository, ObjectMapper mapper,
            @Qualifier("commonAuditingClock") Clock clock, @Value("${spring.application.name}") String producer) {
        return new OutboxWriter(repository, mapper, clock, producer);
    }

    @Bean
    OutboxEventSender outboxEventSender(KafkaTemplate<String, String> kafka, OutboxProperties properties) {
        return KafkaOutboxEventSender.create(kafka, properties.sendTimeout());
    }

    // MeterRegistry 빈이 없는 서비스는 지표 없이 동작한다
    @Bean
    OutboxMetrics outboxMetrics(ObjectProvider<MeterRegistry> registry,
            @Qualifier("commonAuditingClock") Clock clock) {
        var available = registry.getIfAvailable();
        return available == null ? OutboxMetrics.disabled() : new OutboxMetrics(available, clock);
    }

    @Bean
    OutboxRelay outboxRelay(OutboxRepository repository, OutboxEventSender sender,
            @Qualifier("commonAuditingClock") Clock clock, OutboxProperties properties, OutboxMetrics metrics) {
        return new OutboxRelay(repository, sender, clock, properties, OutboxBackoff.withRandomJitter(), metrics);
    }

    // 릴레이 실행기는 릴레이를 켰을 때만 만든다. 종료 대기는 진행 중인 실행이 브로커 확인을 기다리는 최대 시간(전송 시한)보다 길게 잡는다
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "messaging.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
    static class RelaySchedulingConfiguration {
        @Bean
        OutboxRelayScheduler outboxRelayScheduler(OutboxRelay relay, OutboxProperties properties,
                @Value("${messaging.outbox.interval:500ms}") Duration interval, OutboxMetrics metrics,
                @Qualifier("commonAuditingClock") Clock clock) {
            return new OutboxRelayScheduler(relay, interval, properties.sendTimeout().plus(STOP_MARGIN), metrics,
                    clock);
        }
    }

    @Bean
    ProcessedEventRepository processedEventRepository(JdbcTemplate jdbc) {
        return new ProcessedEventRepository(jdbc);
    }

    @Bean
    ProcessedEventService processedEventService(ProcessedEventRepository repository,
            PlatformTransactionManager transactionManager, @Qualifier("commonAuditingClock") Clock clock) {
        return new ProcessedEventService(repository, transactionManager, clock);
    }
}
