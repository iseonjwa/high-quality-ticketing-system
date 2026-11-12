package com.iseonjwa.payment.refund.presentation.messaging;

import com.iseonjwa.common.messaging.consumer.ConsumerContainerFactories;
import com.iseonjwa.common.messaging.consumer.ConsumerErrorHandlers;
import com.iseonjwa.payment.pg.application.PgUnavailableException;
import com.iseonjwa.payment.pg.infrastructure.PgProtectionProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentCommandProperties.class)
class PaymentCommandKafkaConfiguration {
    private static final Logger log = LoggerFactory.getLogger(PaymentCommandKafkaConfiguration.class);

    // 자동결제 허가 대기 간격. 허가를 못 얻은 동안은 횟수 제한 없이 같은 레코드를 다시 읽는다(D6 백프레셔, ISE-89)
    static final long PG_PERMIT_WAIT_MILLIS = 1_000;

    /**
     * {@code payment.command} 토픽 선언. 결제 서비스가 시작할 때 Spring Kafka의 {@code KafkaAdmin}이 만들고, 이미 있는 토픽의
     * 파티션이 선언보다 적으면 늘린다(줄이지는 않는다). 브로커에 닿지 못해도 기동은 막히지 않는다(fail-fast 기본값 false).
     * 파티션을 늘리는 순간 같은 키가 다른 파티션으로 갈 수 있으므로 추첨 자동결제가 도는 중(소비자 랙이 0이 아닐 때)에는 배포하지 않는다.
     *
     * <p>Note: {@code payment.command.dlq}는 선언하지 않는다. common의 DLQ 전송이 파티션을 지정하지 않아(키 기반 배정)
     * 원본 파티션 수와 같을 필요가 없다({@code ConsumerErrorHandlers.retryThenDeadLetter}).
     */
    @Bean
    NewTopic paymentCommandTopic(PaymentCommandProperties properties) {
        return TopicBuilder.name(PaymentCommandListener.TOPIC).partitions(properties.partitions()).replicas(1).build();
    }

    /**
     * {@code payment.command} 전용 컨테이너 팩토리. 공통 부품(common.messaging.consumer)의 "재시도 3회 뒤 DLQ" 처리기에
     * 자동결제 허가 대기를 더해 붙인다. 동시성은 설정값(기본 3)이다. 같은 키는 같은 파티션으로 가고 파티션 하나는 소비자 하나가
     * 순서대로 읽으므로 키(예매 ID) 기준 순서가 지켜진다(events-and-reliability.md 7절).
     */
    @Bean(PaymentCommandListener.CONTAINER_FACTORY)
    ConcurrentKafkaListenerContainerFactory<Object, Object> paymentCommandListenerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate, PaymentCommandProperties properties,
            PgProtectionProperties pgProtection) {
        var factory = ConsumerContainerFactories.recordAck(configurer, consumerFactory,
                paymentCommandErrorHandler(kafkaTemplate, ConsumerErrorHandlers.defaultRetryBackOff()));
        factory.setConcurrency(properties.concurrency());
        warnIfBillingPermitsAreFewer(properties.concurrency(), pgProtection.billing().maxConcurrentCalls());
        return factory;
    }

    // 소비자가 자동결제 허가 수보다 많으면 일부 소비자는 허가를 기다리게 된다(기동은 막지 않는다)
    static void warnIfBillingPermitsAreFewer(int concurrency, int billingMaxConcurrentCalls) {
        if (billingMaxConcurrentCalls < concurrency) {
            log.warn("pg-billing 동시 허가({})가 payment.command 소비자 동시성({})보다 적다. 일부 소비자가 허가를 기다린다",
                    billingMaxConcurrentCalls, concurrency);
        }
    }

    /**
     * {@code payment.command.dlq} 전용 컨테이너 팩토리. DLQ 처리기는 건너뛰면 환불 의무가 사라지므로 복구기 없이 무제한
     * 백오프로 재시도하는 처리기를 붙인다(events-and-reliability.md 5절).
     */
    @Bean(PaymentCommandDlqListener.CONTAINER_FACTORY)
    ConcurrentKafkaListenerContainerFactory<Object, Object> paymentCommandDlqListenerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<Object, Object> consumerFactory) {
        return ConsumerContainerFactories.recordAck(configurer, consumerFactory, ConsumerErrorHandlers.retryForever());
    }

    static DefaultErrorHandler paymentCommandErrorHandler(KafkaOperations<?, ?> template, BackOff backOff) {
        return paymentCommandErrorHandler(template, backOff, PG_PERMIT_WAIT_MILLIS);
    }

    // 허가 대기 간격을 바꿀 수 있는 형태. 테스트가 짧은 값을 넘긴다
    static DefaultErrorHandler paymentCommandErrorHandler(KafkaOperations<?, ?> template, BackOff backOff,
            long permitWaitMillis) {
        var handler = ConsumerErrorHandlers.retryThenDeadLetter(template, backOff);
        // Note: 허가 거절은 PG 장애·포화가 풀리면 성공하는 일시 상태이고, 결제 행을 만들기 전이라 다시 해도 안전하다.
        // 일반 재시도(3회 뒤 DLQ)를 쓰면 당첨 결제가 DLQ로 빠져 유실처럼 보이므로 이 예외만 무제한으로 기다린다.
        // 오프셋을 커밋하지 않으므로 키 순서도 유지된다. 대기 중에는 그 파티션의 뒤 메시지(환불 포함)만 멈춘다. 다른 파티션의 소비자는 계속 처리한다
        handler.setBackOffFunction((record, exception) -> isPgUnavailable(exception)
                ? new FixedBackOff(permitWaitMillis, FixedBackOff.UNLIMITED_ATTEMPTS) : null);
        return handler;
    }

    private static boolean isPgUnavailable(Throwable exception) {
        for (var cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof PgUnavailableException) return true;
        }
        return false;
    }
}
