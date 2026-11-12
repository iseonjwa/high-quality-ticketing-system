package com.iseonjwa.payment.refund.presentation.messaging;

import com.iseonjwa.payment.pg.application.PgUnavailableException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.support.TopicPartitionOffset;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// payment.command 소비자 동시성 3에서 키별 순서, 동시 처리, 허가 대기를 확인한다(ADR 0051, ISE-236).
// 브로커 없이 실제 컨테이너와 오류 처리기를 쓰고 소비자만 MockConsumer로 바꾼다. 파티션은 직접 지정(assign)한다.
// 실제 브로커와 토픽 파티션 확인은 compose에서 한다.
class PaymentCommandConcurrencyTest {
    private static final String TOPIC = "payment.command";
    private static final int PARTITIONS = 3;
    private static final long TIMEOUT_SECONDS = 15;

    private final List<ReplayConsumer> consumers = new CopyOnWriteArrayList<>();
    private ConcurrentMessageListenerContainer<String, String> container;
    private KafkaOperations<Object, Object> dlq;

    @AfterEach
    void stop() {
        if (container != null) container.stop();
    }

    // (a) 같은 키의 명령은 한 파티션이라 한 소비자가 순서대로 처리한다. (b) 다른 파티션의 명령은 동시에 처리된다
    @Test
    void 서로_다른_파티션은_동시에_처리하고_같은_파티션은_순서를_지킨다() throws Exception {
        var otherStarted = new CountDownLatch(2);
        var releaseFirst = new CountDownLatch(1);
        var started = Collections.synchronizedList(new ArrayList<String>());
        var done = Collections.synchronizedList(new ArrayList<String>());
        var firstFinished = new CountDownLatch(1);
        MessageListener<String, String> listener = record -> {
            started.add(record.value());
            if (record.value().equals("A1-billing")) {
                // 다른 두 파티션의 명령이 이 명령이 끝나기 전에 시작돼야 풀린다. 소비자 하나가 직렬로 처리하면 풀리지 않는다
                await(releaseFirst);
            } else if (record.value().startsWith("B1") || record.value().startsWith("C1")) {
                otherStarted.countDown();
            }
            done.add(record.value());
            if (record.value().equals("A1-billing")) firstFinished.countDown();
        };
        start(listener, 3, new FixedBackOff(1, 0));
        // 키 A(예매 800)는 파티션 0에 자동결제 → 환불 순서로, 키 B·C는 파티션 1·2에 하나씩
        send(0, "800", "A1-billing");
        send(0, "800", "A2-refund");
        send(1, "801", "B1-billing");
        send(2, "802", "C1-billing");

        assertTrue(otherStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "다른 파티션의 명령이 동시에 시작돼야 한다");
        // A1이 아직 끝나지 않았으므로 같은 파티션의 A2는 시작하지 않았다
        assertTrue(!started.contains("A2-refund"), "같은 파티션의 뒤 명령이 앞 명령보다 먼저 시작했다: " + started);
        releaseFirst.countDown();
        assertTrue(firstFinished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        awaitSize(done, 4);

        assertTrue(done.indexOf("A1-billing") < done.indexOf("A2-refund"), "같은 키의 처리 순서: " + done);
    }

    // (c) 허가를 못 얻은 자동결제는 DLQ로 가지 않고 허가가 나면 처리된다. 대기 중에는 그 파티션의 뒤 명령만 멈춘다
    @Test
    void 허가를_못_얻은_명령은_DLQ로_가지_않고_허가가_나면_처리되며_다른_파티션은_멈추지_않는다() throws Exception {
        var attempts = new AtomicInteger();
        var done = Collections.synchronizedList(new ArrayList<String>());
        var otherDone = new CountDownLatch(1);
        MessageListener<String, String> listener = record -> {
            if (record.value().equals("A1-billing") && attempts.incrementAndGet() <= 3) {
                throw new PgUnavailableException("pg-billing 허가를 얻지 못했다");
            }
            done.add(record.value());
            if (record.value().equals("B1-billing")) otherDone.countDown();
        };
        start(listener, 3, new FixedBackOff(1, 0));
        send(0, "800", "A1-billing");
        send(0, "800", "A2-refund");
        send(1, "801", "B1-billing");

        assertTrue(otherDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        awaitSize(done, 3);

        assertEquals(4, attempts.get(), "허가 거절 3번 뒤 4번째에 처리된다");
        assertTrue(done.indexOf("A1-billing") < done.indexOf("A2-refund"), "기다린 명령이 뒤 명령보다 먼저다: " + done);
        verify(dlq, never()).send(any(ProducerRecord.class));
    }

    // (d) 원본 파티션 1·2에서 실패한 레코드의 DLQ 전송은 파티션을 지정하지 않아 DLQ 토픽의 파티션 수와 상관없다
    @Test
    @SuppressWarnings("unchecked")
    void 파티션_1과_2에서_실패한_레코드는_파티션을_지정하지_않고_DLQ로_보낸다() {
        var template = mock(KafkaOperations.class);
        when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        var handler = PaymentCommandKafkaConfiguration.paymentCommandErrorHandler(template, new FixedBackOff(0, 0), 1);
        var failure = new org.springframework.kafka.listener.ListenerExecutionFailedException("x",
                new com.iseonjwa.common.messaging.consumer.NonRetryableMessageException("검증 실패"));

        for (var partition : List.of(1, 2)) {
            var record = new ConsumerRecord<Object, Object>(TOPIC, partition, 7L, "800", "{}");
            handler.handleRemaining(failure, List.of(record), mock(org.apache.kafka.clients.consumer.Consumer.class),
                    mock(org.springframework.kafka.listener.MessageListenerContainer.class));
        }

        var captor = org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template, org.mockito.Mockito.times(2)).send(captor.capture());
        for (var sent : captor.getAllValues()) {
            assertEquals("payment.command.dlq", sent.topic());
            assertEquals(null, sent.partition(), "파티션을 지정하지 않는다");
        }
    }

    @SuppressWarnings("unchecked")
    private void start(MessageListener<String, String> listener, int concurrency, FixedBackOff backOff) {
        dlq = mock(KafkaOperations.class);
        when(dlq.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        var factory = mock(ConsumerFactory.class);
        when(factory.createConsumer(any(), any(), any(), any(Properties.class))).thenAnswer(invocation -> {
            var consumer = new ReplayConsumer();
            var beginnings = new HashMap<TopicPartition, Long>();
            for (var partition = 0; partition < PARTITIONS; partition++) beginnings.put(new TopicPartition(TOPIC, partition), 0L);
            consumer.updateBeginningOffsets(beginnings);
            consumers.add(consumer);
            return consumer;
        });
        when(factory.getConfigurationProperties()).thenReturn(new HashMap<>());
        var offsets = new ArrayList<TopicPartitionOffset>();
        for (var partition = 0; partition < PARTITIONS; partition++) offsets.add(new TopicPartitionOffset(TOPIC, partition));
        var properties = new ContainerProperties(offsets.toArray(TopicPartitionOffset[]::new));
        properties.setGroupId("payment.command");
        properties.setAckMode(ContainerProperties.AckMode.RECORD);
        properties.setIdleBetweenPolls(5);
        properties.setMessageListener(listener);
        container = new ConcurrentMessageListenerContainer<>(factory, properties);
        container.setConcurrency(concurrency);
        container.setCommonErrorHandler(PaymentCommandKafkaConfiguration.paymentCommandErrorHandler(dlq, backOff, 1));
        container.start();
    }

    // 파티션을 받은 소비자에 레코드를 넣는다. MockConsumer는 poll 작업 안에서만 레코드를 더할 수 있다
    private void send(int partition, String key, String value) throws InterruptedException {
        var topicPartition = new TopicPartition(TOPIC, partition);
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            for (var consumer : consumers) {
                if (consumer.assignment().contains(topicPartition)) {
                    var offset = nextOffsets.computeIfAbsent(partition, p -> new AtomicInteger()).getAndIncrement();
                    consumer.schedulePollTask(() -> consumer.addRecord(
                            new ConsumerRecord<>(TOPIC, partition, offset, key, value)));
                    return;
                }
            }
            Thread.sleep(5);
        }
        throw new AssertionError("파티션 " + partition + "을 받은 소비자가 없다");
    }

    private final ConcurrentHashMap<Integer, AtomicInteger> nextOffsets = new ConcurrentHashMap<>();

    // MockConsumer는 읽어 준 레코드를 버려서 오류 처리기의 seek(되돌려 다시 읽기)을 흉내 내지 못한다. seek하면 그 위치부터 다시 내어 준다
    static final class ReplayConsumer extends MockConsumer<String, String> {
        private final java.util.Map<TopicPartition, List<ConsumerRecord<String, String>>> log = new ConcurrentHashMap<>();

        ReplayConsumer() {
            super("earliest");
        }

        @Override
        public synchronized void addRecord(ConsumerRecord<String, String> record) {
            log.computeIfAbsent(new TopicPartition(record.topic(), record.partition()), tp -> new CopyOnWriteArrayList<>())
                    .add(record);
            super.addRecord(record);
        }

        @Override
        public synchronized void seek(TopicPartition partition, long offset) {
            super.seek(partition, offset);
            for (var record : log.getOrDefault(partition, List.of())) {
                if (record.offset() >= offset) super.addRecord(record);
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw new AssertionError("시간 안에 풀리지 않았다");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitSize(List<String> list, int size) throws InterruptedException {
        var deadline = Duration.ofSeconds(TIMEOUT_SECONDS).toNanos() + System.nanoTime();
        while (list.size() < size && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(size, list.size(), list.toString());
    }
}
