package com.iseonjwa.payment.refund.infrastructure.messaging;

import com.iseonjwa.common.messaging.consumer.ConsumerContainerFactories;
import com.iseonjwa.common.messaging.consumer.ConsumerErrorHandlers;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

@Configuration(proxyBeanMethods = false)
class PaymentCommandKafkaConfiguration {
    /**
     * {@code payment.command} 전용 컨테이너 팩토리. 공통 부품(common.messaging.consumer)의 "재시도 3회 뒤 DLQ" 처리기를 붙인다.
     * 동시성 1은 키 기반 순서 때문이다(events-and-reliability.md 7절).
     */
    @Bean(PaymentCommandListener.CONTAINER_FACTORY)
    ConcurrentKafkaListenerContainerFactory<Object, Object> paymentCommandListenerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate) {
        return ConsumerContainerFactories.recordAck(configurer, consumerFactory,
                ConsumerErrorHandlers.retryThenDeadLetter(kafkaTemplate));
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
}
