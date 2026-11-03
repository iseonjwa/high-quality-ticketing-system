package com.iseonjwa.payment.refund.infrastructure.messaging;

import com.iseonjwa.common.messaging.inbox.ProcessedEventRepository;
import com.iseonjwa.payment.observability.PaymentMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import com.iseonjwa.payment.refund.application.DeadLetterService;
import com.iseonjwa.common.messaging.consumer.ConsumerErrorHandlers;
import com.iseonjwa.common.messaging.consumer.NonRetryableMessageException;
import com.iseonjwa.payment.refund.application.RefundRequestService;
import com.iseonjwa.payment.refund.domain.DeadLetterRepository;
import com.iseonjwa.payment.refund.domain.DeadLetterResolution;
import com.iseonjwa.payment.refund.domain.DeadLetterStatus;
import com.iseonjwa.payment.support.RefundFixtures;
import com.iseonjwa.payment.support.RefundFixtures.CreatedPayment;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.FixedBackOff;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// DLQ 레코드를 직접 만들어 리스너를 부른다. 실제 테스트 DB를 쓰고 커밋하므로 테스트 트랜잭션으로 감싸지 않는다.
// Note: 이 범위의 원본 파티션 번호(99)는 이 테스트만 쓴다. dead_letters는 이 번호로 지운다.
// Note: 다른 통합 테스트와 같은 설정이라 스프링 컨텍스트를 공유한다(테스트 DB 연결 한도).
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class PaymentCommandDlqListenerTest {
    private static final int TEST_PARTITION = 99;
    private static final String ORIGIN_TOPIC = "payment.command";
    private static final String DLQ_TOPIC = "payment.command.dlq";

    @Autowired
    PaymentCommandDlqListener listener;
    @Autowired
    PaymentMetrics metrics;
    @Autowired
    MeterRegistry meters;
    @Autowired
    RefundRequestService refundRequests;
    @Autowired
    DeadLetterRepository deadLetters;
    @Autowired
    ProcessedEventRepository processedEvents;
    @Autowired
    TransactionTemplate transaction;
    @Autowired
    @Qualifier("commonAuditingClock")
    Clock clock;
    @Autowired
    JdbcTemplate jdbc;

    private RefundFixtures fixtures;
    private final List<UUID> eventIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fixtures = new RefundFixtures(jdbc);
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    // dead_letters가 환불을 참조하므로 먼저 지운다
    private void cleanUp() {
        jdbc.update("DELETE FROM dead_letters WHERE source_partition = ?", TEST_PARTITION);
        for (var eventId : eventIds) {
            jdbc.update("DELETE FROM dead_letters WHERE event_id = ?", eventId);
        }
        fixtures.cleanUp(eventIds);
    }

    @Test
    void 검증을_통과하고_환불이_없으면_환불을_만들고_AUTO_PERSISTED로_닫는다() {
        var payment = fixtures.payment("APPROVED");
        var eventId = newEventId();

        listener.onMessage(withOrigin(message(eventId, payment, 50000), 7, 100));

        var refund = jdbc.queryForMap("SELECT * FROM refunds WHERE payment_id = ?", payment.paymentId());
        assertEquals("REQUESTED", refund.get("status"));
        assertEquals("DLQ", refund.get("source"));
        assertEquals(eventId, refund.get("source_event_id"));
        assertEquals(1, fixtures.refundHistoryCount(payment.paymentId()));
        var deadLetter = deadLetters.findByEventKey(eventId.toString()).orElseThrow();
        assertEquals(DeadLetterStatus.RESOLVED, deadLetter.status());
        assertEquals(DeadLetterResolution.AUTO_PERSISTED, deadLetter.resolution());
        assertEquals(refund.get("id"), deadLetter.refundId());
        assertEquals(eventId, deadLetter.eventId());
        assertEquals("RefundRequested", deadLetter.eventType());
        assertEquals(ORIGIN_TOPIC, deadLetter.sourceTopic());
        assertEquals(TEST_PARTITION, deadLetter.sourcePartition());
        assertEquals(100L, deadLetter.sourceOffset());
        assertEquals(String.valueOf(payment.reservationId()), deadLetter.messageKey());
        assertTrue(deadLetter.payloadRaw().contains(eventId.toString()));
        assertTrue(deadLetter.resolvedAt() != null);
        assertEquals(1, processedInDlqGroup(eventId));
    }

    @Test
    void 검증을_통과하고_환불이_있으면_DUPLICATE로_닫고_환불은_그대로다() {
        var payment = fixtures.payment("APPROVED");
        listener.onMessage(withOrigin(message(newEventId(), payment, 50000), 7, 100));
        var second = newEventId();

        listener.onMessage(withOrigin(message(second, payment, 50000), 7, 101));

        var deadLetter = deadLetters.findByEventKey(second.toString()).orElseThrow();
        assertEquals(DeadLetterStatus.RESOLVED, deadLetter.status());
        assertEquals(DeadLetterResolution.DUPLICATE, deadLetter.resolution());
        assertEquals(jdbc.queryForObject("SELECT id FROM refunds WHERE payment_id = ?", Long.class, payment.paymentId()),
                deadLetter.refundId());
        assertEquals(1, fixtures.refundCount(payment.paymentId()));
        assertEquals(1, fixtures.refundHistoryCount(payment.paymentId()));
        assertEquals(1, processedInDlqGroup(second));
    }

    @Test
    void 금액이_맞지_않으면_예외_없이_OPEN으로_남기고_환불은_만들지_않는다() {
        var payment = fixtures.payment("APPROVED");
        var eventId = newEventId();

        listener.onMessage(withOrigin(message(eventId, payment, 49999), 7, 100));

        var deadLetter = assertOpen(eventId.toString());
        assertTrue(deadLetter.reason().contains("amount"), deadLetter.reason());
        assertEquals(0, fixtures.refundCount(payment.paymentId()));
        assertEquals(1, processedInDlqGroup(eventId));
    }

    @Test
    void 결제가_없으면_예외_없이_OPEN으로_남긴다() {
        var eventId = newEventId();
        var missing = new CreatedPayment(999_999_999L, 8_899_999L);

        listener.onMessage(withOrigin(message(eventId, missing, 50000), 7, 100));

        var deadLetter = assertOpen(eventId.toString());
        assertTrue(deadLetter.reason().contains("결제가 없습니다"), deadLetter.reason());
    }

    @Test
    void 결제가_APPROVED가_아니고_환불도_없으면_예외_없이_OPEN으로_남긴다() {
        var payment = fixtures.payment("REQUESTED");
        var eventId = newEventId();

        listener.onMessage(withOrigin(message(eventId, payment, 50000), 7, 100));

        var deadLetter = assertOpen(eventId.toString());
        assertTrue(deadLetter.reason().contains("결제 상태"), deadLetter.reason());
        assertEquals(0, fixtures.refundCount(payment.paymentId()));
        assertEquals(0, fixtures.refundHistoryCount(payment.paymentId()));
    }

    @Test
    void 깨진_JSON은_원문을_보존해_OPEN으로_남기고_event_key는_원본_위치다() {
        listener.onMessage(withOrigin("{not json", 7, 123));

        var deadLetter = assertOpen(ORIGIN_TOPIC + "-" + TEST_PARTITION + "-123");
        assertNull(deadLetter.eventId());
        assertNull(deadLetter.eventType());
        assertEquals("{not json", deadLetter.payloadRaw());
        assertTrue(deadLetter.reason().startsWith("역직렬화·봉투 오류"), deadLetter.reason());
        assertEquals(ORIGIN_TOPIC, deadLetter.sourceTopic());
        assertEquals(123L, deadLetter.sourceOffset());
    }

    @Test
    void 원본_위치_헤더가_없으면_DLQ_레코드_자신의_위치를_쓴다() {
        listener.onMessage(new ConsumerRecord<>(DLQ_TOPIC, TEST_PARTITION, 5L, "k", "{not json"));

        var deadLetter = assertOpen(DLQ_TOPIC + "-" + TEST_PARTITION + "-5");
        assertEquals(DLQ_TOPIC, deadLetter.sourceTopic());
        assertEquals(5L, deadLetter.sourceOffset());
        assertEquals("k", deadLetter.messageKey());
    }

    @Test
    void 같은_DLQ_레코드를_다시_받아도_dead_letters와_환불은_한_건이다() {
        var payment = fixtures.payment("APPROVED");
        var eventId = newEventId();
        var record = withOrigin(message(eventId, payment, 50000), 7, 100);

        listener.onMessage(record);
        listener.onMessage(record);

        assertEquals(1, deadLetterCount(eventId));
        assertEquals(1, fixtures.refundCount(payment.paymentId()));
        assertEquals(1, fixtures.refundHistoryCount(payment.paymentId()));
        assertEquals(1, processedInDlqGroup(eventId));
    }

    @Test
    void 이미_처리한_메시지는_재전달돼도_다시_평가하지_않는다() {
        var payment = fixtures.payment("REQUESTED");
        var eventId = newEventId();
        var record = withOrigin(message(eventId, payment, 50000), 7, 100);
        listener.onMessage(record);
        assertOpen(eventId.toString());
        // 첫 처리 뒤에 결제가 승인 상태가 되어도, 같은 DLQ 메시지의 재전달은 아무것도 하지 않고 끝난다
        jdbc.update("UPDATE payments SET status = 'APPROVED', pg_tx_id = 'pg-redeliver', approved_at = now(), "
                + "pg_approved_amount = 50000, next_check_at = NULL WHERE id = ?", payment.paymentId());

        listener.onMessage(record);

        assertOpen(eventId.toString());
        assertEquals(0, fixtures.refundCount(payment.paymentId()));
        assertEquals(1, deadLetterCount(eventId));
    }

    @Test
    void 깨진_메시지를_다시_받아도_한_건이다() {
        var record = withOrigin("{not json", 7, 123);

        listener.onMessage(record);
        listener.onMessage(record);

        assertEquals(1, countByPartition());
    }

    @Test
    void 같은_eventId가_다른_DLQ_레코드로_두_번_오면_dead_letters는_한_건이다() {
        var payment = fixtures.payment("APPROVED");
        var eventId = newEventId();

        listener.onMessage(withOrigin(message(eventId, payment, 50000), 7, 100));
        listener.onMessage(withOrigin(message(eventId, payment, 50000), 7, 200));

        assertEquals(1, deadLetterCount(eventId));
        assertEquals(1, fixtures.refundCount(payment.paymentId()));
    }

    @Test
    void 깨진_메시지는_원본_위치가_다르면_각각_한_건이다() {
        listener.onMessage(withOrigin("{not json", 7, 123));
        listener.onMessage(withOrigin("{not json", 7, 124));

        assertEquals(2, countByPartition());
    }

    @Test
    void NUL_문자가_든_메시지도_예외_없이_OPEN으로_남긴다() {
        listener.onMessage(withOrigin("{bad\u0000json", 7, 125));

        var deadLetter = assertOpen(ORIGIN_TOPIC + "-" + TEST_PARTITION + "-125");
        assertEquals("{bad�json", deadLetter.payloadRaw());
    }

    @Test
    void 사유가_길면_500자로_자른다() {
        var service = new DeadLetterService(deadLetters, refundRequests, processedEvents, transaction, clock);

        service.handleUnparsable(new com.iseonjwa.payment.refund.application.DeadLetterInput(ORIGIN_TOPIC, TEST_PARTITION,
                126L, null, "raw"), "x".repeat(1000));

        var deadLetter = assertOpen(ORIGIN_TOPIC + "-" + TEST_PARTITION + "-126");
        assertEquals(500, deadLetter.reason().length());
    }

    @Test
    void 환불_생성에서_DB_오류가_나면_예외가_나가고_아무것도_남지_않으며_다시_처리하면_정상이다() {
        var payment = fixtures.payment("APPROVED");
        var eventId = newEventId();
        var failing = spy(refundRequests);
        doThrow(new DataAccessResourceFailureException("DB 연결 실패")).doCallRealMethod()
                .when(failing).create(any(), any(), any(), any());
        var flaky = new PaymentCommandDlqListener(parser(), new DeadLetterService(deadLetters, failing, processedEvents,
                transaction, clock), metrics);
        var record = withOrigin(message(eventId, payment, 50000), 7, 100);

        var failuresBefore = handlerFailures();

        assertThrows(DataAccessResourceFailureException.class, () -> flaky.onMessage(record));

        assertEquals(failuresBefore + 1, handlerFailures(), "예외로 끝난 처리는 처리기 실패로 센다");
        assertEquals(0, deadLetterCount(eventId));
        assertEquals(0, fixtures.refundCount(payment.paymentId()));
        assertEquals(0, processedInDlqGroup(eventId));

        flaky.onMessage(record);

        assertEquals(failuresBefore + 1, handlerFailures(), "성공한 재처리는 실패로 세지 않는다");
        assertEquals(DeadLetterResolution.AUTO_PERSISTED, deadLetters.findByEventKey(eventId.toString()).orElseThrow().resolution());
        assertEquals(1, fixtures.refundCount(payment.paymentId()));
        assertEquals(1, processedInDlqGroup(eventId));
    }

    @Test
    void 결과_반영에서_DB_오류가_나면_이미_만든_환불과_처리_기록도_함께_롤백된다() {
        var payment = fixtures.payment("APPROVED");
        var eventId = newEventId();
        var failing = new ResolveFailsOnce(deadLetters);
        var flaky = new PaymentCommandDlqListener(parser(), new DeadLetterService(failing, refundRequests, processedEvents,
                transaction, clock), metrics);
        var record = withOrigin(message(eventId, payment, 50000), 7, 100);

        assertThrows(DataAccessResourceFailureException.class, () -> flaky.onMessage(record));

        assertEquals(0, deadLetterCount(eventId));
        assertEquals(0, fixtures.refundCount(payment.paymentId()));
        assertEquals(0, fixtures.refundHistoryCount(payment.paymentId()));
        assertEquals(0, processedInDlqGroup(eventId));

        flaky.onMessage(record);

        assertEquals(1, deadLetterCount(eventId));
        assertEquals(1, fixtures.refundCount(payment.paymentId()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 실제_복구기가_붙인_원본_위치_헤더를_그대로_읽는다() {
        // payment.command 오류 처리기가 DLQ로 보낼 때 Spring Kafka가 붙이는 헤더를 그대로 받아 리스너에 넣는다
        var kafka = mock(KafkaOperations.class);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        var original = new ConsumerRecord<Object, Object>(ORIGIN_TOPIC, TEST_PARTITION, 4_000_000_000L, "k", "{not json");
        var handler = ConsumerErrorHandlers.retryThenDeadLetter(kafka, new FixedBackOff(1, 1));
        handler.handleRemaining(new ListenerExecutionFailedException("failed", new NonRetryableMessageException("봉투 오류")),
                List.of(original), mock(Consumer.class), mock(MessageListenerContainer.class));
        var captor = org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(captor.capture());
        var sent = (ProducerRecord<Object, Object>) captor.getValue();
        var dlqRecord = new ConsumerRecord<String, String>(DLQ_TOPIC, 0, 9L, "k", "{not json");
        sent.headers().forEach(header -> dlqRecord.headers().add(header));

        listener.onMessage(dlqRecord);

        var deadLetter = assertOpen(ORIGIN_TOPIC + "-" + TEST_PARTITION + "-4000000000");
        assertEquals(4_000_000_000L, deadLetter.sourceOffset());
    }

    // 저장소 빈은 예외 변환 프록시라 스파이로 만들 수 없다. resolve만 첫 호출에 실패하는 위임 객체를 쓴다
    private static final class ResolveFailsOnce implements DeadLetterRepository {
        private final DeadLetterRepository delegate;
        private boolean failed;

        ResolveFailsOnce(DeadLetterRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public java.util.Optional<Long> insertOpen(com.iseonjwa.payment.refund.domain.NewDeadLetter deadLetter, java.time.Instant now) {
            return delegate.insertOpen(deadLetter, now);
        }

        @Override
        public boolean updateOpenReason(Long id, String reason) {
            return delegate.updateOpenReason(id, reason);
        }

        @Override
        public boolean resolve(Long id, DeadLetterResolution resolution, Long refundId, java.time.Instant now) {
            if (!failed) {
                failed = true;
                throw new DataAccessResourceFailureException("DB 연결 실패");
            }
            return delegate.resolve(id, resolution, refundId, now);
        }

        @Override
        public java.util.Optional<com.iseonjwa.payment.refund.domain.DeadLetter> findByEventKey(String eventKey) {
            return delegate.findByEventKey(eventKey);
        }

        @Override
        public long countOpen() {
            return delegate.countOpen();
        }
    }

    private com.iseonjwa.payment.refund.domain.DeadLetter assertOpen(String eventKey) {
        var deadLetter = deadLetters.findByEventKey(eventKey).orElseThrow(() -> new AssertionError("dead_letters 없음: " + eventKey));
        assertEquals(DeadLetterStatus.OPEN, deadLetter.status());
        assertNull(deadLetter.resolution());
        assertNull(deadLetter.refundId());
        assertNull(deadLetter.resolvedAt());
        return deadLetter;
    }

    private int deadLetterCount(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM dead_letters WHERE event_key = ?", Integer.class, eventId.toString());
    }

    private int countByPartition() {
        return jdbc.queryForObject("SELECT count(*) FROM dead_letters WHERE source_partition = ?", Integer.class, TEST_PARTITION);
    }

    private int processedInDlqGroup(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ? AND consumer_group = 'payment.command-dlq'",
                Integer.class, eventId);
    }

    private UUID newEventId() {
        var eventId = UUID.randomUUID();
        eventIds.add(eventId);
        return eventId;
    }

    private static RefundRequestedMessageParser parser() {
        return new RefundRequestedMessageParser(tools.jackson.databind.json.JsonMapper.builder().build());
    }

    private static String message(UUID eventId, CreatedPayment payment, long amount) {
        return """
                {"eventId":"%s","eventType":"RefundRequested","version":1,"occurredAt":"2026-11-02T11:00:01.123Z",
                 "producer":"reservation-service","traceId":"abc",
                 "payload":{"reservationId":%d,"paymentId":%d,"userId":%d,"amount":%d,"currency":"KRW","reason":"CANCELED"}}"""
                .formatted(eventId, payment.reservationId(), payment.paymentId(), RefundFixtures.USER_ID, amount);
    }

    // DLQ 레코드. 위치는 DLQ 토픽 것이고, 원본 위치(partition 99, offset)는 Spring Kafka가 붙이는 헤더 형식으로 넣는다
    private static ConsumerRecord<String, String> withOrigin(String value, int dlqOffset, long originOffset) {
        var record = new ConsumerRecord<String, String>(DLQ_TOPIC, 0, dlqOffset, keyOf(value), value);
        record.headers().add("kafka_dlt-original-topic", ORIGIN_TOPIC.getBytes(StandardCharsets.UTF_8));
        record.headers().add("kafka_dlt-original-partition", ByteBuffer.allocate(Integer.BYTES).putInt(TEST_PARTITION).array());
        record.headers().add("kafka_dlt-original-offset", ByteBuffer.allocate(Long.BYTES).putLong(originOffset).array());
        return record;
    }

    private static String keyOf(String value) {
        var marker = "\"reservationId\":";
        var at = value.indexOf(marker);
        return at < 0 ? null : value.substring(at + marker.length()).split("[,}]")[0];
    }

    private double handlerFailures() {
        return meters.get("payment.dlq.handler.failures").tag("topic", PaymentCommandDlqListener.TOPIC).counter()
                .count();
    }
}
