package com.iseonjwa.payment.refund.infrastructure.messaging;

import com.iseonjwa.common.messaging.consumer.ConsumerContainerFactories;
import com.iseonjwa.common.messaging.consumer.ConsumerErrorHandlers;
import com.iseonjwa.payment.pg.application.PgUnavailableException;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
class PaymentCommandKafkaConfiguration {
    // 자동결제 허가 대기 간격. 허가를 못 얻은 동안은 횟수 제한 없이 같은 레코드를 다시 읽는다(D6 백프레셔, ISE-89)
    static final long PG_PERMIT_WAIT_MILLIS = 1_000;

    /**
     * {@code payment.command} 전용 컨테이너 팩토리. 공통 부품(common.messaging.consumer)의 "재시도 3회 뒤 DLQ" 처리기에
     * 자동결제 허가 대기를 더해 붙인다. 동시성 1은 키 기반 순서 때문이다(events-and-reliability.md 7절).
     */
    @Bean(PaymentCommandListener.CONTAINER_FACTORY)
    ConcurrentKafkaListenerContainerFactory<Object, Object> paymentCommandListenerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate) {
        return ConsumerContainerFactories.recordAck(configurer, consumerFactory,
                paymentCommandErrorHandler(kafkaTemplate, ConsumerErrorHandlers.defaultRetryBackOff()));
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
        var handler = ConsumerErrorHandlers.retryThenDeadLetter(template, backOff);
        // Note: 허가 거절은 PG 장애·포화가 풀리면 성공하는 일시 상태이고, 결제 행을 만들기 전이라 다시 해도 안전하다.
        // 일반 재시도(3회 뒤 DLQ)를 쓰면 당첨 결제가 DLQ로 빠져 유실처럼 보이므로 이 예외만 무제한으로 기다린다.
        // 오프셋을 커밋하지 않으므로 키 순서도 유지된다. 대기 중에는 같은 파티션의 뒤 메시지(환불 포함)도 멈춘다
        handler.setBackOffFunction((record, exception) -> isPgUnavailable(exception)
                ? new FixedBackOff(PG_PERMIT_WAIT_MILLIS, FixedBackOff.UNLIMITED_ATTEMPTS) : null);
        return handler;
    }

    private static boolean isPgUnavailable(Throwable exception) {
        for (var cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof PgUnavailableException) return true;
        }
        return false;
    }
}
