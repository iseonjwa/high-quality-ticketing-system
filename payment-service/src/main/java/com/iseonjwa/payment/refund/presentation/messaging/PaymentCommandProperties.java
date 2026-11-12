package com.iseonjwa.payment.refund.presentation.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code payment.command} 토픽과 리스너 설정(ADR 0051 결정 1·2, ISE-236·238). 값은 {@code [가정]}이다.
 *
 * @param partitions  토픽 파티션 수. 결제 서비스가 시작할 때 {@code NewTopic}으로 선언한다(이미 있는 토픽이 더 적으면 늘린다)
 * @param concurrency 리스너 동시성. 키(예매 ID)가 같은 메시지는 같은 파티션이라 순서가 지켜지고, 소비자는 파티션 수보다 많아도 일이 없으므로 파티션 수 이하여야 한다
 */
@ConfigurationProperties(prefix = "payment.messaging.command")
public record PaymentCommandProperties(Integer partitions, Integer concurrency) {
    private static final int DEFAULT_PARTITIONS = 3;
    private static final int DEFAULT_CONCURRENCY = 3;

    public PaymentCommandProperties {
        partitions = partitions != null ? partitions : DEFAULT_PARTITIONS;
        concurrency = concurrency != null ? concurrency : DEFAULT_CONCURRENCY;
        if (partitions < 1) {
            throw new IllegalArgumentException("payment.messaging.command.partitions는 1 이상이어야 합니다");
        }
        if (concurrency < 1) {
            throw new IllegalArgumentException("payment.messaging.command.concurrency는 1 이상이어야 합니다");
        }
        if (concurrency > partitions) {
            throw new IllegalArgumentException("payment.messaging.command.concurrency(" + concurrency
                    + ")는 partitions(" + partitions + ") 이하여야 합니다. 남는 소비자는 일을 받지 못합니다");
        }
    }
}
