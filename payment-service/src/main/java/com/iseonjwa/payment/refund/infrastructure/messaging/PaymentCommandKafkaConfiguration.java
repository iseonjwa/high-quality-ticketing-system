package com.iseonjwa.payment.refund.infrastructure.messaging;

import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

@Configuration(proxyBeanMethods = false)
class PaymentCommandKafkaConfiguration {
    /**
     * {@code payment.command} 전용 컨테이너 팩토리. Boot가 읽은 공통 설정(ack-mode record, 수동 오프셋 등)을 먼저 적용하고
     * 오류 처리기만 이 리스너 것으로 바꾼다. 동시성은 1이다(키 기반 순서, events-and-reliability.md 7절).
     */
    @Bean(PaymentCommandListener.CONTAINER_FACTORY)
    ConcurrentKafkaListenerContainerFactory<Object, Object> paymentCommandListenerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate) {
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        configurer.configure(factory, consumerFactory);
        factory.setConcurrency(1);
        factory.setCommonErrorHandler(
                PaymentCommandErrorHandlers.create(kafkaTemplate, PaymentCommandErrorHandlers.productionBackOff()));
        return factory;
    }
}
