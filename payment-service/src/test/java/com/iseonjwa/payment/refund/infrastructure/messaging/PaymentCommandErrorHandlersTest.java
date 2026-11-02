package com.iseonjwa.payment.refund.infrastructure.messaging;

import com.iseonjwa.payment.pg.application.PgUnavailableException;
import com.iseonjwa.payment.refund.application.NonRetryableMessageException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.FixedBackOff;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 브로커 없이 오류 처리기를 단위로 확인한다. 컨테이너가 record 리스너 오류를 넘기는 경로(handleRemaining)를 그대로 부른다.
// 실제 브로커 확인은 compose에서 한다.
class PaymentCommandErrorHandlersTest {
    private static final String KEY = "8800001";
    private static final String RAW_VALUE = "{broken json";

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
        record = new ConsumerRecord<>("payment.command", 3, 42L, KEY, RAW_VALUE);
    }

    @Test
    void 영구_오류는_첫_실패에_재시도_없이_DLQ로_보낸다() {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        var handler = PaymentCommandErrorHandlers.create(kafka, new FixedBackOff(1, 3));

        handler.handleRemaining(failure(new NonRetryableMessageException("검증 실패")), List.of(record), consumer, container);

        var sent = sentRecords(1).get(0);
        assertEquals("payment.command.dlq", sent.topic());
        assertNull(sent.partition(), "파티션을 지정하지 않는다");
        assertEquals(KEY, sent.key());
        assertEquals(RAW_VALUE, sent.value());
        // 복구했으므로 같은 레코드를 다시 읽도록 되돌리지 않는다
        verify(consumer, never()).seek(any(TopicPartition.class), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void 일시_오류는_재시도_횟수를_다_쓴_뒤에_DLQ로_보낸다() {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        var handler = PaymentCommandErrorHandlers.create(kafka, new FixedBackOff(1, 3));
        var transientFailure = failure(new DataAccessResourceFailureException("DB 연결 실패"));

        // 처음 실패와 재시도 2번은 되돌려 다시 읽고(RecordInRetryException) 보내지 않는다
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertRetryPending(() -> handler.handleRemaining(transientFailure, List.of(record), consumer, container));
            verify(kafka, never()).send(any(ProducerRecord.class));
        }
        verify(consumer, times(3)).seek(new TopicPartition("payment.command", 3), 42L);

        // 재시도 3회를 다 쓴 다음 실패에서 보낸다
        handler.handleRemaining(transientFailure, List.of(record), consumer, container);

        var sent = sentRecords(1).get(0);
        assertEquals("payment.command.dlq", sent.topic());
        assertNull(sent.partition());
        assertEquals(KEY, sent.key());
        assertEquals(RAW_VALUE, sent.value());
    }

    // ISE-89 백프레셔: 자동결제 허가 거절은 재시도 횟수(3회)를 넘어도 DLQ로 보내지 않고 같은 레코드를 계속 다시 읽는다
    @Test
    void 허가_거절은_횟수_제한_없이_다시_읽고_DLQ로_보내지_않는다() {
        var handler = PaymentCommandErrorHandlers.create(kafka, new FixedBackOff(1, 3));
        var permitRejected = failure(new PgUnavailableException("pg-approve 허가 거절"));

        for (int attempt = 1; attempt <= 10; attempt++) {
            assertRetryPending(() -> handler.handleRemaining(permitRejected, List.of(record), consumer, container));
        }

        verify(kafka, never()).send(any(ProducerRecord.class));
        verify(consumer, times(10)).seek(new TopicPartition("payment.command", 3), 42L);
    }

    @Test
    void DLQ_전송이_실패하면_복구가_실패로_끝나_다시_처리_대상이_된다() {
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));
        var handler = PaymentCommandErrorHandlers.create(kafka, new FixedBackOff(1, 3));

        // 영구 오류라 바로 복구를 시도하지만 전송이 실패한다. 건너뛰지 않고 같은 레코드를 되돌려 다시 읽는다
        assertRetryPending(() -> handler.handleRemaining(
                failure(new NonRetryableMessageException("검증 실패")), List.of(record), consumer, container));

        verify(kafka).send(any(ProducerRecord.class));
        verify(consumer).seek(new TopicPartition("payment.command", 3), 42L);
    }

    @Test
    void 운영_BackOff는_1_2_4초_간격_3회_재시도다() {
        var backOff = PaymentCommandErrorHandlers.productionBackOff().start();

        assertEquals(1_000, backOff.nextBackOff());
        assertEquals(2_000, backOff.nextBackOff());
        assertEquals(4_000, backOff.nextBackOff());
        assertEquals(BackOffExecution.STOP, backOff.nextBackOff());
        assertEquals(1_000, PaymentCommandErrorHandlers.RETRY_INITIAL_INTERVAL_MILLIS);
        assertEquals(2.0, PaymentCommandErrorHandlers.RETRY_MULTIPLIER);
        assertEquals(3, PaymentCommandErrorHandlers.MAX_RETRIES);
    }

    @SuppressWarnings("unchecked")
    private List<ProducerRecord<Object, Object>> sentRecords(int expected) {
        var captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, times(expected)).send(captor.capture());
        return captor.getAllValues().stream().map(r -> (ProducerRecord<Object, Object>) r).toList();
    }

    // 아직 복구되지 않아 같은 레코드를 다시 처리해야 할 때 던지는 예외다. 이 예외 클래스는 패키지 비공개라 이름으로 확인한다
    private static void assertRetryPending(org.junit.jupiter.api.function.Executable handling) {
        var thrown = assertThrows(RuntimeException.class, handling);
        assertEquals("RecordInRetryException", thrown.getClass().getSimpleName());
    }

    // 컨테이너는 리스너 예외를 ListenerExecutionFailedException으로 감싸 넘긴다
    private ListenerExecutionFailedException failure(Exception cause) {
        return new ListenerExecutionFailedException("Listener failed", cause);
    }
}
