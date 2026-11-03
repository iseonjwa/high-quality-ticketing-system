package com.iseonjwa.payment.refund.presentation.messaging;

import com.iseonjwa.payment.pg.application.PgUnavailableException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// payment.command 처리기가 공통 "재시도 뒤 DLQ"에 더한 자동결제 허가 대기(ISE-89)만 확인한다. 공통 규칙 자체는 common 테스트가 본다.
class PaymentCommandErrorHandlerTest {
    private KafkaOperations<Object, Object> kafka;
    private Consumer<?, ?> consumer;
    private MessageListenerContainer container;
    private ConsumerRecord<Object, Object> record;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafka = mock(KafkaOperations.class);
        consumer = mock(Consumer.class);
        container = mock(MessageListenerContainer.class);
        record = new ConsumerRecord<>("payment.command", 3, 42L, "9900001", "{\"eventType\":\"BillingPaymentRequested\"}");
    }

    // 재시도 횟수(3회)를 넘어도 DLQ로 보내지 않고 같은 레코드를 계속 다시 읽는다
    @Test
    void 허가_거절은_횟수_제한_없이_다시_읽고_DLQ로_보내지_않는다() {
        var handler = PaymentCommandKafkaConfiguration.paymentCommandErrorHandler(kafka, new FixedBackOff(1, 3));
        var permitRejected = failure(new PgUnavailableException("pg-approve 허가 거절"));

        for (int attempt = 1; attempt <= 10; attempt++) {
            assertRetryPending(() -> handler.handleRemaining(permitRejected, List.of(record), consumer, container));
        }

        verify(kafka, never()).send(any(ProducerRecord.class));
        verify(consumer, times(10)).seek(new TopicPartition("payment.command", 3), 42L);
    }

    // 허가 대기는 그 예외에만 걸린다. 다른 일시 오류는 공통 규칙대로 재시도를 다 쓴 뒤 DLQ로 간다
    @Test
    void 다른_일시_오류는_재시도를_다_쓴_뒤_DLQ로_보낸다() {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        var handler = PaymentCommandKafkaConfiguration.paymentCommandErrorHandler(kafka, new FixedBackOff(1, 3));
        var transientFailure = failure(new DataAccessResourceFailureException("DB 연결 실패"));

        for (int attempt = 1; attempt <= 3; attempt++) {
            assertRetryPending(() -> handler.handleRemaining(transientFailure, List.of(record), consumer, container));
        }
        handler.handleRemaining(transientFailure, List.of(record), consumer, container);

        verify(kafka, times(1)).send(any(ProducerRecord.class));
    }

    // 아직 복구되지 않아 같은 레코드를 다시 처리해야 할 때 던지는 예외다. 이 예외 클래스는 패키지 비공개라 이름으로 확인한다
    private static void assertRetryPending(org.junit.jupiter.api.function.Executable handling) {
        var thrown = assertThrows(RuntimeException.class, handling);
        assertEquals("RecordInRetryException", thrown.getClass().getSimpleName());
    }

    // 컨테이너는 리스너 예외를 ListenerExecutionFailedException으로 감싸 넘긴다
    private static ListenerExecutionFailedException failure(Exception cause) {
        return new ListenerExecutionFailedException("Listener failed", cause);
    }
}
