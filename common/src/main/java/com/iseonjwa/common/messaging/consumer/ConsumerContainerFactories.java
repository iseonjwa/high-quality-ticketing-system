package com.iseonjwa.common.messaging.consumer;

import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;

/** 리스너 전용 컨테이너 팩토리를 만든다. 서비스가 자기 {@code @Configuration}의 {@code @Bean}에서 부른다. */
public final class ConsumerContainerFactories {
    private ConsumerContainerFactories() {}

    /**
     * Boot가 읽은 공통 설정을 먼저 적용한 뒤 ack-mode RECORD, 오류 처리기, 동시성 1로 고정한다.
     *
     * <p>Note: ack-mode는 공통 설정(config-repo)에도 record로 있지만 여기서 다시 고정한다. "처리에 성공한 레코드만 커밋하고,
     * DLQ 전송에 실패하면 커밋하지 않는다"는 소비자의 정확성이 이 값에 달려 있어, 공유 설정 파일이 바뀌어도 오프셋이 흔들리지
     * 않게 한다. 동시성 1은 키 기반 순서를 지키는 기본값이며, 늘려야 하면 서비스가 반환된 팩토리에서 바꾼다.
     */
    public static ConcurrentKafkaListenerContainerFactory<Object, Object> recordAck(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<Object, Object> consumerFactory,
            CommonErrorHandler handler) {
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        configurer.configure(factory, consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setConcurrency(1);
        factory.setCommonErrorHandler(handler);
        return factory;
    }
}
