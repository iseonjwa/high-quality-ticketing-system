package com.iseonjwa.payment.infrastructure.housekeeping;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 보관 정리 설정(events-and-reliability.md 7·8절, ADR 0022 결정 5·6).
 *
 * <p>Note: {@code topicRetention}과 {@code dlqRetention}은 이 앱이 쓰는 값이 아니라 브로커의 토픽 보관 설정을 운영자가 맞춰
 * 적는 값이다. 앱이 브로커 설정을 직접 읽지 못하므로 {@code processedEventsRetention}이 그보다 충분히 긴지 기동 때 비교하는
 * 근거로만 쓴다. 브로커 쪽 보관을 늘리고 이 값을 같이 올리지 않으면 비교가 의미를 잃는다.
 *
 * @param enabled                  false면 정리 작업을 등록하지 않는다(보관 기간 검증은 그대로 한다)
 * @param interval                 한 주기가 끝난 뒤 다음 주기까지의 간격
 * @param batchSize                배치 한 번에 지우는 최대 행 수
 * @param maxBatchesPerTick        한 주기에 한 작업이 지우는 최대 배치 수
 * @param pauseBetweenBatches      같은 주기 안에서 다음 배치로 넘어가기 전에 쉬는 시간
 * @param outboxSentRetention      {@code SENT} Outbox를 남기는 기간
 * @param processedEventsRetention 처리 이력을 남기는 기간. 토픽·DLQ 보관보다 길어야 한다
 * @param topicRetention           {@code payment.command} 토픽의 보관 기간(운영자가 브로커 설정과 맞춰 적는다)
 * @param dlqRetention             {@code payment.command.dlq} 토픽의 보관 기간(운영자가 브로커 설정과 맞춰 적는다)
 */
@ConfigurationProperties(prefix = "payment.housekeeping")
public record HousekeepingProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("10m") Duration interval,
        @DefaultValue("1000") int batchSize,
        @DefaultValue("100") int maxBatchesPerTick,
        @DefaultValue("100ms") Duration pauseBetweenBatches,
        @DefaultValue("7d") Duration outboxSentRetention,
        @DefaultValue("14d") Duration processedEventsRetention,
        @DefaultValue("7d") Duration topicRetention,
        @DefaultValue("7d") Duration dlqRetention) {
    public HousekeepingProperties {
        requirePositive(interval, "interval");
        requirePositive(outboxSentRetention, "outbox-sent-retention");
        requirePositive(processedEventsRetention, "processed-events-retention");
        requirePositive(topicRetention, "topic-retention");
        requirePositive(dlqRetention, "dlq-retention");
        if (pauseBetweenBatches.isNegative()) {
            throw new IllegalArgumentException("payment.housekeeping.pause-between-batches는 0 이상이어야 합니다");
        }
        if (batchSize < 1) throw new IllegalArgumentException("payment.housekeeping.batch-size는 1 이상이어야 합니다");
        if (maxBatchesPerTick < 1) {
            throw new IllegalArgumentException("payment.housekeeping.max-batches-per-tick은 1 이상이어야 합니다");
        }
        // 처리 이력이 먼저 지워지면, 아직 브로커에 남은 메시지가 재전달될 때 중복 처리를 이력으로 막지 못한다
        var brokerRetention = topicRetention.compareTo(dlqRetention) >= 0 ? topicRetention : dlqRetention;
        if (processedEventsRetention.compareTo(brokerRetention) <= 0) {
            throw new IllegalArgumentException("payment.housekeeping.processed-events-retention(" + processedEventsRetention
                    + ")은 topic-retention(" + topicRetention + ")과 dlq-retention(" + dlqRetention
                    + ") 중 긴 쪽보다 길어야 합니다");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("payment.housekeeping." + name + "은 0보다 커야 합니다");
        }
    }
}
