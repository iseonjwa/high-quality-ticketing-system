package com.iseonjwa.reservation.seathold.presentation.messaging;

import com.iseonjwa.common.messaging.consumer.ConsumerContainerFactories;
import com.iseonjwa.common.messaging.consumer.ConsumerErrorHandlers;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

@Configuration(proxyBeanMethods = false)
class SeatHoldKafkaConfiguration {

    /**
     * {@code seat-hold.request} 토픽 선언. 선언이 없으면 브로커 기본값(파티션 1개)으로 자동 생성되어 모든 구역이 소비자 한 줄에 선다.
     * 시작할 때 {@code KafkaAdmin}이 만들고, 이미 있는 토픽의 파티션이 선언보다 적으면 늘린다(줄이지는 않는다).
     *
     * <p>Note: 파티션은 소비자 동시성보다 넉넉히 둔다. 나중에 파티션을 늘리면 같은 키가 다른 파티션으로 옮겨 키별 순서가 잠시
     * 흔들린다. 동시성은 설정으로 바로 올릴 수 있지만 파티션은 그렇지 않다. 처리 중인 요청이 있을 때는 파티션을 늘리지 않는다.
     * DLQ 토픽은 선언하지 않는다. DLQ 전송은 파티션을 지정하지 않아 원본과 파티션 수가 같을 필요가 없다.
     */
    @Bean
    NewTopic seatHoldRequestTopic(@Value("${seat-hold.consumer.partitions:6}") int partitions) {
        return TopicBuilder.name(SeatHoldRequestListener.TOPIC).partitions(partitions).replicas(1).build();
    }

    /**
     * {@code seat-hold.request} 전용 컨테이너 팩토리. 1·2·4초 3회 재시도 뒤 {@code seat-hold.request.dlq}로 보낸다.
     *
     * <p>Note: 동시성 하나가 처리 중에 DB 커넥션 하나를 쥔다. 풀(5개)을 접수 POST, 결과 폴링, 좌석 조회와 나눠 쓰므로
     * 기본값을 2로 둔다. 같은 키는 같은 파티션이라 동시성을 올려도 키별 순서는 지켜진다.
     */
    @Bean(SeatHoldRequestListener.CONTAINER_FACTORY)
    ConcurrentKafkaListenerContainerFactory<Object, Object> seatHoldRequestListenerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate, @Value("${seat-hold.consumer.concurrency:2}") int concurrency) {
        var factory = ConsumerContainerFactories.recordAck(configurer, consumerFactory,
                ConsumerErrorHandlers.retryThenDeadLetter(kafkaTemplate));
        factory.setConcurrency(concurrency);
        return factory;
    }

    /**
     * {@code seat-hold.request.dlq} 전용 컨테이너 팩토리. 다시 DLQ로 보내지 않고, DB 같은 인프라 오류가 풀릴 때까지 같은
     * 레코드를 다시 처리한다. 끝내 닫지 못한 요청도 30초 시한 정리가 닫는다.
     */
    @Bean(SeatHoldRequestDlqListener.CONTAINER_FACTORY)
    ConcurrentKafkaListenerContainerFactory<Object, Object> seatHoldRequestDlqListenerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<Object, Object> consumerFactory) {
        return ConsumerContainerFactories.recordAck(configurer, consumerFactory, ConsumerErrorHandlers.retryForever());
    }
}
