package com.iseonjwa.common.messaging.consumer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ConsumerContainerFactoriesTest {
    @Test
    @SuppressWarnings("unchecked")
    void 공통_설정이_다른_ack_mode를_넣어도_RECORD로_고정하고_처리기와_동시성_1을_설정한다() {
        var configurer = mock(ConcurrentKafkaListenerContainerFactoryConfigurer.class);
        var consumerFactory = mock(ConsumerFactory.class);
        // Boot 공통 설정이 BATCH를 넣은 상황을 흉내 낸다
        doAnswer(invocation -> {
            var target = invocation.getArgument(0, org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory.class);
            target.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
            target.setConcurrency(5);
            return null;
        }).when(configurer).configure(any(), any());
        var handler = new DefaultErrorHandler();

        var factory = ConsumerContainerFactories.recordAck(configurer, consumerFactory, handler);

        verify(configurer).configure(factory, consumerFactory);
        assertEquals(ContainerProperties.AckMode.RECORD, factory.getContainerProperties().getAckMode());
        assertSame(handler, ReflectionTestUtils.getField(factory, "commonErrorHandler"));
        assertEquals(1, ReflectionTestUtils.getField(factory, "concurrency"));
    }
}
