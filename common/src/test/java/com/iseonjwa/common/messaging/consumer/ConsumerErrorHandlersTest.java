package com.iseonjwa.common.messaging.consumer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.FixedBackOff;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 브로커 없이 오류 처리기를 단위로 확인한다. 컨테이너가 record 리스너 오류를 넘기는 경로(handleRemaining)를 그대로 부른다.
// 실제 브로커 확인은 compose에서 한다.
class ConsumerErrorHandlersTest {
    private static final String KEY = "8800001";
    private static final String RAW_VALUE = "{broken json";
    private static final int RETRIES = 100;

    private KafkaOperations<Object, Object> kafka;
    private Consumer<?, ?> consumer;
    private MessageListenerContainer container;
    private ConsumerRecord<Object, Object> record;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafka = mock(KafkaOperations.class);
        consumer = mock(Consumer.class);
        container = mock(MessageListenerContainer.class);
        record = new ConsumerRecord<>("payment.command", 3, 42L, KEY, RAW_VALUE);
        logs = new ListAppender<>();
        logs.start();
        logger = (Logger) LoggerFactory.getLogger(ConsumerErrorHandlers.class);
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    @Test
    void DLQ_토픽_이름은_원본_토픽에_dlq를_붙인다() {
        assertEquals("payment.command.dlq", ConsumerErrorHandlers.deadLetterTopic("payment.command"));
        assertEquals("payment.result.dlq", ConsumerErrorHandlers.deadLetterTopic("payment.result"));
    }

    @Test
    void 영구_오류는_첫_실패에_재시도_없이_원본_토픽의_dlq로_보낸다() {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        var handler = ConsumerErrorHandlers.retryThenDeadLetter(kafka, new FixedBackOff(1, 3));

        handler.handleRemaining(failure(new NonRetryableMessageException("검증 실패")), List.of(record), consumer, container);

        var sent = sentRecords(1).get(0);
        assertEquals("payment.command.dlq", sent.topic());
        assertNull(sent.partition(), "파티션을 지정하지 않는다");
        assertEquals(KEY, sent.key());
        assertEquals(RAW_VALUE, sent.value());
        // 복구했으므로 같은 레코드를 다시 읽도록 되돌리지 않는다
        verify(consumer, never()).seek(any(TopicPartition.class), anyLong());
    }

    @Test
    void DLQ_레코드에_원본_토픽_파티션_오프셋_예외가_헤더로_붙는다() {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        var handler = ConsumerErrorHandlers.retryThenDeadLetter(kafka, new FixedBackOff(1, 3));

        handler.handleRemaining(failure(new NonRetryableMessageException("검증 실패")), List.of(record), consumer, container);

        var headers = sentRecords(1).get(0).headers();
        assertEquals("payment.command", headerValue(headers.lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC)));
        assertNotNull(headers.lastHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION));
        assertNotNull(headers.lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET));
        assertNotNull(headers.lastHeader(KafkaHeaders.DLT_EXCEPTION_FQCN));
        assertNotNull(headers.lastHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE));
    }

    @Test
    void 일시_오류는_재시도_횟수를_다_쓴_뒤에_DLQ로_보낸다() {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        var handler = ConsumerErrorHandlers.retryThenDeadLetter(kafka, new FixedBackOff(1, 3));
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

    @Test
    void DLQ_전송이_실패하면_복구가_실패로_끝나_다시_처리_대상이_된다() {
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));
        var handler = ConsumerErrorHandlers.retryThenDeadLetter(kafka, new FixedBackOff(1, 3));

        // 영구 오류라 바로 복구를 시도하지만 전송이 실패한다. 건너뛰지 않고 같은 레코드를 되돌려 다시 읽는다
        assertRetryPending(() -> handler.handleRemaining(
                failure(new NonRetryableMessageException("검증 실패")), List.of(record), consumer, container));

        verify(kafka).send(any(ProducerRecord.class));
        verify(consumer).seek(new TopicPartition("payment.command", 3), 42L);
        assertTrue(logs.list.stream().noneMatch(e -> e.getFormattedMessage().contains("MESSAGE_DEAD_LETTERED")),
                "전송이 실패했는데 DLQ로 보냈다고 기록하면 안 된다");
    }

    @Test
    void DLQ로_보낸_뒤에_ERROR_로그를_한_줄_남기고_값_원문은_넣지_않는다() {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        var handler = ConsumerErrorHandlers.retryThenDeadLetter(kafka, new FixedBackOff(1, 3));

        handler.handleRemaining(failure(new NonRetryableMessageException("검증 실패")), List.of(record), consumer, container);

        var deadLettered = logs.list.stream().filter(e -> e.getFormattedMessage().contains("MESSAGE_DEAD_LETTERED")).toList();
        assertEquals(1, deadLettered.size());
        assertEquals(Level.ERROR, deadLettered.get(0).getLevel());
        assertEquals("event=MESSAGE_DEAD_LETTERED topic=payment.command partition=3 offset=42 key=8800001 "
                + "dlqTopic=payment.command.dlq cause=" + NonRetryableMessageException.class.getName(),
                deadLettered.get(0).getFormattedMessage());
        assertFalse(deadLettered.get(0).getFormattedMessage().contains(RAW_VALUE));
    }

    @Test
    void 일시_오류의_재시도_중에는_DLQ_로그를_남기지_않는다() {
        var handler = ConsumerErrorHandlers.retryThenDeadLetter(kafka, new FixedBackOff(1, 3));

        assertRetryPending(() -> handler.handleRemaining(
                failure(new DataAccessResourceFailureException("DB 연결 실패")), List.of(record), consumer, container));

        assertTrue(logs.list.stream().noneMatch(e -> e.getFormattedMessage().contains("MESSAGE_DEAD_LETTERED")));
    }

    @Test
    void 운영_BackOff는_1_2_4초_간격_3회_재시도다() {
        var backOff = ConsumerErrorHandlers.defaultRetryBackOff().start();

        assertEquals(1_000, backOff.nextBackOff());
        assertEquals(2_000, backOff.nextBackOff());
        assertEquals(4_000, backOff.nextBackOff());
        assertEquals(BackOffExecution.STOP, backOff.nextBackOff());
        assertEquals(1_000, ConsumerErrorHandlers.RETRY_INITIAL_INTERVAL_MILLIS);
        assertEquals(2.0, ConsumerErrorHandlers.RETRY_MULTIPLIER);
        assertEquals(3, ConsumerErrorHandlers.MAX_RETRIES);
    }

    @Test
    void 무제한_백오프는_1_2_4초로_늘다가_60초에서_멈추고_끝나지_않는다() {
        var execution = ConsumerErrorHandlers.defaultUnlimitedBackOff().start();

        for (var interval : new long[] {1_000, 2_000, 4_000, 8_000, 16_000, 32_000, 60_000, 60_000}) {
            assertEquals(interval, execution.nextBackOff());
        }
        for (int attempt = 0; attempt < RETRIES; attempt++) {
            var next = execution.nextBackOff();
            assertNotEquals(BackOffExecution.STOP, next, "시도 " + attempt + "번째에 STOP");
            assertEquals(60_000, next);
        }
    }

    @Test
    void 무제한_처리기는_일시_오류를_몇_번을_반복해도_건너뛰지_않고_같은_레코드를_다시_읽는다() {
        var handler = ConsumerErrorHandlers.retryForever(ConsumerErrorHandlers.unlimitedBackOff(1, 1.0, 1));
        var dlqRecord = new ConsumerRecord<Object, Object>("payment.command.dlq", 2, 42L, "k", "v");
        var failure = failure(new DataAccessResourceFailureException("DB 연결 실패"));

        for (int attempt = 0; attempt < RETRIES; attempt++) {
            assertRetryPending(() -> handler.handleRemaining(failure, List.of(dlqRecord), consumer, container));
        }

        verify(consumer, times(RETRIES)).seek(new TopicPartition("payment.command.dlq", 2), 42L);
        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    @Test
    void 무제한_처리기는_변환_예외와_영구_오류_표시도_재시도한다() {
        var handler = ConsumerErrorHandlers.retryForever(ConsumerErrorHandlers.unlimitedBackOff(1, 1.0, 1));

        // DefaultErrorHandler는 기본으로 이 예외들을 재시도 없이 복구(건너뛰기)한다. 여기서는 그렇게 하지 않아야 한다
        for (Exception cause : new Exception[] {new NonRetryableMessageException("내용 오류"), new ClassCastException("변환"),
                new IllegalArgumentException("인자"),
                new DeserializationException("역직렬화", new byte[0], false, new RuntimeException())}) {
            assertRetryPending(() -> handler.handleRemaining(failure(cause), List.of(record), consumer, container));
        }
    }

    @SuppressWarnings("unchecked")
    private List<ProducerRecord<Object, Object>> sentRecords(int expected) {
        var captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, times(expected)).send(captor.capture());
        return captor.getAllValues().stream().map(r -> (ProducerRecord<Object, Object>) r).toList();
    }

    private static String headerValue(Header header) {
        return new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
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
