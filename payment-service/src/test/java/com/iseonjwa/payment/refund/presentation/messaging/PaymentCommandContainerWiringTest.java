package com.iseonjwa.payment.refund.presentation.messaging;

import com.iseonjwa.common.messaging.consumer.NonRetryableMessageException;
import com.iseonjwa.payment.pg.application.PgUnavailableException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// Note: 테스트 프로필은 listener.auto-startup=false라 브로커 없이 컨텍스트가 뜬다. 컨테이너는 만들어지지만 시작하지 않는다.
// KafkaTemplate을 스파이로 바꿔 전송만 가로채고, 리스너 컨테이너에 붙은 오류 처리기가 실제로 이 리스너의 DLQ 규칙인지 동작으로 확인한다.
// Note: 테스트 프로필은 설정 서버를 읽지 않아 공통 ack-mode(record)가 없다. 그래도 RECORD가 나오면 팩토리가 직접 고정한 것이다.
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class PaymentCommandContainerWiringTest {
    @Autowired
    KafkaListenerEndpointRegistry registry;
    @Autowired
    ApplicationContext context;
    @MockitoSpyBean
    KafkaTemplate<?, ?> kafkaTemplate;

    @Test
    void payment_command_리스너는_전용_팩토리의_ack_mode_RECORD_컨테이너로_등록된다() {
        var container = paymentCommandContainer();

        assertEquals(ContainerProperties.AckMode.RECORD, container.getContainerProperties().getAckMode());
        assertEquals("payment.command", container.getGroupId());
        assertEquals(List.of("payment.command"), List.of(container.getContainerProperties().getTopics()));
        assertEquals(false, container.isAutoStartup());
    }

    @Test
    void 컨테이너의_오류_처리기는_영구_오류를_payment_command_dlq로_보낸다() {
        doReturn(CompletableFuture.completedFuture(null)).when(kafkaTemplate).send(any(ProducerRecord.class));
        CommonErrorHandler handler = ((AbstractMessageListenerContainer<?, ?>) paymentCommandContainer()).getCommonErrorHandler();
        assertNotNull(handler);
        var record = new ConsumerRecord<Object, Object>("payment.command", 0, 5L, "8800002", "raw");

        handler.handleRemaining(new ListenerExecutionFailedException("failed", new NonRetryableMessageException("검증 실패")),
                List.of(record), mock(Consumer.class), mock(MessageListenerContainer.class));

        @SuppressWarnings("unchecked")
        var captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        assertEquals("payment.command.dlq", captor.getValue().topic());
        assertEquals("8800002", captor.getValue().key());
    }

    // ISE-89 백프레셔가 실제 컨테이너 처리기에 붙었는지 본다(허가 거절은 DLQ로 보내지 않고 다시 읽는다)
    @Test
    void 컨테이너의_오류_처리기는_자동결제_허가_거절을_DLQ로_보내지_않는다() {
        CommonErrorHandler handler = ((AbstractMessageListenerContainer<?, ?>) paymentCommandContainer()).getCommonErrorHandler();
        var record = new ConsumerRecord<Object, Object>("payment.command", 0, 6L, "9900001", "raw");

        var thrown = assertThrows(RuntimeException.class, () -> handler.handleRemaining(
                new ListenerExecutionFailedException("failed", new PgUnavailableException("pg-approve 허가 거절")),
                List.of(record), mock(Consumer.class), mock(MessageListenerContainer.class)));

        assertEquals("RecordInRetryException", thrown.getClass().getSimpleName());
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    @Test
    void 오류_처리기를_전역_CommonErrorHandler_빈으로_등록하지_않는다() {
        assertTrue(context.getBeansOfType(CommonErrorHandler.class).isEmpty());
    }

    @Test
    void DLQ_리스너는_전용_팩토리의_ack_mode_RECORD_컨테이너로_등록된다() {
        var container = dlqContainer();

        assertEquals(ContainerProperties.AckMode.RECORD, container.getContainerProperties().getAckMode());
        assertEquals("payment.command-dlq", container.getGroupId());
        assertEquals(List.of("payment.command.dlq"), List.of(container.getContainerProperties().getTopics()));
        assertEquals(false, container.isAutoStartup());
        assertTrue(context.containsBean("paymentCommandDlqListenerFactory"));
    }

    @Test
    void DLQ_컨테이너의_오류_처리기는_payment_command_컨테이너의_것과_다르다() {
        var dlqHandler = ((AbstractMessageListenerContainer<?, ?>) dlqContainer()).getCommonErrorHandler();
        var commandHandler = ((AbstractMessageListenerContainer<?, ?>) paymentCommandContainer()).getCommonErrorHandler();

        assertNotNull(dlqHandler);
        assertNotSame(commandHandler, dlqHandler);
    }

    @Test
    void DLQ_컨테이너의_오류_처리기는_복구기가_없어_다른_토픽으로_보내지_않고_재시도_대기한다() {
        CommonErrorHandler handler = ((AbstractMessageListenerContainer<?, ?>) dlqContainer()).getCommonErrorHandler();
        var record = new ConsumerRecord<Object, Object>("payment.command.dlq", 0, 5L, "8800002", "raw");

        // 내용 오류 예외여도 DLQ 처리기는 건너뛰거나 보내지 않고 같은 레코드를 다시 처리해야 한다
        var thrown = assertThrows(RuntimeException.class, () -> handler.handleRemaining(
                new ListenerExecutionFailedException("failed", new NonRetryableMessageException("검증 실패")),
                List.of(record), mock(Consumer.class), mock(MessageListenerContainer.class)));

        assertEquals("RecordInRetryException", thrown.getClass().getSimpleName());
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    private MessageListenerContainer dlqContainer() {
        return registry.getAllListenerContainers().stream()
                .filter(c -> "payment.command-dlq".equals(c.getGroupId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("payment.command-dlq 컨테이너가 없다"));
    }

    private MessageListenerContainer paymentCommandContainer() {
        return registry.getAllListenerContainers().stream()
                .filter(c -> "payment.command".equals(c.getGroupId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("payment.command 컨테이너가 없다"));
    }
}
