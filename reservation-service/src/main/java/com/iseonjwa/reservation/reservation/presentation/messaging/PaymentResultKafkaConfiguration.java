package com.iseonjwa.reservation.reservation.presentation.messaging;

import com.iseonjwa.common.messaging.consumer.ConsumerContainerFactories;
import com.iseonjwa.common.messaging.consumer.ConsumerErrorHandlers;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

@Configuration(proxyBeanMethods = false)
class PaymentResultKafkaConfiguration {

    /**
     * {@code payment.result} 전용 컨테이너 팩토리. 공통 부품의 "1·2·4초 3회 재시도 뒤 {@code payment.result.dlq}" 처리기를 붙인다.
     * DLQ에 쌓인 메시지를 이어받는 처리기는 ISE-74에서 만든다.
     */
    @Bean(PaymentResultListener.CONTAINER_FACTORY)
    ConcurrentKafkaListenerContainerFactory<Object, Object> paymentResultListenerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate) {
        return ConsumerContainerFactories.recordAck(configurer, consumerFactory,
                ConsumerErrorHandlers.retryThenDeadLetter(kafkaTemplate));
    }
}
