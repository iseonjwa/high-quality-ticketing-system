package com.iseonjwa.payment.refund.infrastructure.messaging;

import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;

@Configuration(proxyBeanMethods = false)
class PaymentCommandKafkaConfiguration {
    /**
     * {@code payment.command} 전용 컨테이너 팩토리. Boot가 읽은 공통 설정을 먼저 적용하고 오류 처리기를 이 리스너 것으로
     * 바꾼다. 동시성은 1이다(키 기반 순서, events-and-reliability.md 7절).
     *
     * <p>Note: ack-mode는 공통 설정(config-repo)에도 record로 있지만 여기서 다시 고정한다. "처리에 성공한 레코드만 커밋하고,
     * DLQ 전송에 실패하면 커밋하지 않는다"는 이 소비자의 정확성이 이 값에 달려 있어, 공유 설정 파일이 바뀌어도 흔들리지 않게 한다.
     */
    @Bean(PaymentCommandListener.CONTAINER_FACTORY)
    ConcurrentKafkaListenerContainerFactory<Object, Object> paymentCommandListenerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate) {
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        configurer.configure(factory, consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setConcurrency(1);
        factory.setCommonErrorHandler(
                PaymentCommandErrorHandlers.create(kafkaTemplate, PaymentCommandErrorHandlers.productionBackOff()));
        return factory;
    }
}
