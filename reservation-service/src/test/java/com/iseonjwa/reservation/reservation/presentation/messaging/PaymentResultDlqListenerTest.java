package com.iseonjwa.reservation.reservation.presentation.messaging;

import com.iseonjwa.reservation.reservation.application.PaymentResult;
import com.iseonjwa.reservation.reservation.application.PaymentResultService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.CannotCreateTransactionException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// payment.result DLQ 처리기(ISE-74)를 실제 PostgreSQL로 확인한다. 결제 결과 반영은 목으로 바꿔 실패를 주입한다.
// 리스너 컨테이너는 테스트에서 꺼져 있어 onMessage를 직접 부른다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class PaymentResultDlqListenerTest {
    private static final String SOURCE_TOPIC = "payment.result";

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PaymentResultDlqListener listener;
    @MockitoBean
    PaymentResultService paymentResults;

    // 테스트마다 겹치지 않는 원본 오프셋
    private final long offset = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM dead_letters WHERE source_topic = ? AND source_offset = ?", SOURCE_TOPIC, offset);
    }

    @Test
    void 다시_처리에_성공하면_RESOLVED로_남긴다() {
        var eventId = UUID.randomUUID();

        listener.onMessage(dlqRecord(approved(eventId)));

        verify(paymentResults).handle(any(PaymentResult.class));
        var row = row(eventId.toString());
        assertEquals("RESOLVED", row.get("status"));
        assertEquals("REPLAYED", row.get("resolution"));
        assertEquals(eventId, row.get("event_id"));
        assertEquals(0, ((Number) row.get("source_partition")).intValue());
        assertEquals(offset, ((Number) row.get("source_offset")).longValue());
    }

    @Test
    void 다시_처리가_실패하면_OPEN으로_남기고_예외를_삼킨다() {
        var eventId = UUID.randomUUID();
        doThrow(new IllegalStateException("처리 중에 예매 상태가 바뀌었습니다")).when(paymentResults).handle(any());

        listener.onMessage(dlqRecord(approved(eventId)));

        var row = row(eventId.toString());
        assertEquals("OPEN", row.get("status"));
        assertTrue(((String) row.get("reason")).startsWith("다시 처리 실패: IllegalStateException"));
    }

    @Test
    void 봉투를_읽지_못하면_다시_처리하지_않고_원본_위치를_키로_OPEN으로_남긴다() {
        listener.onMessage(dlqRecord("not json"));

        verify(paymentResults, never()).handle(any());
        var row = row(SOURCE_TOPIC + "-0-" + offset);
        assertEquals("OPEN", row.get("status"));
        assertEquals("not json", row.get("payload_raw"));
        assertTrue(((String) row.get("reason")).startsWith("역직렬화·봉투 오류: "));
    }

    @Test
    void 같은_DLQ_메시지가_두_번_와도_한_건만_남긴다() {
        var eventId = UUID.randomUUID();

        listener.onMessage(dlqRecord(approved(eventId)));
        listener.onMessage(dlqRecord(approved(eventId)));

        verify(paymentResults, times(2)).handle(any());
        assertEquals(1, count());
    }

    @Test
    void DB_인프라_오류는_기록하지_않고_던져_컨테이너가_다시_처리하게_한다() {
        var eventId = UUID.randomUUID();
        doThrow(new QueryTimeoutException("DB 응답 없음")).when(paymentResults).handle(any());

        assertThrows(QueryTimeoutException.class, () -> listener.onMessage(dlqRecord(approved(eventId))));

        assertEquals(0, count());
    }

    // DB가 꺼졌을 때 트랜잭션을 시작하지 못하면 DataAccessException이 아닌 이 예외가 난다(#282 리뷰)
    @Test
    void 트랜잭션을_시작하지_못한_DB_연결_실패도_기록하지_않고_던진다() {
        var eventId = UUID.randomUUID();
        doThrow(new CannotCreateTransactionException("연결을 얻지 못했습니다")).when(paymentResults).handle(any());

        assertThrows(CannotCreateTransactionException.class,
                () -> listener.onMessage(dlqRecord(approved(eventId))));

        assertEquals(0, count());
    }

    // 저장이 내용 때문에 실패하면 retryForever가 같은 레코드를 끝없이 다시 처리해 파티션이 멈춘다(#282 리뷰)
    @Test
    void NUL_문자와_긴_키도_치환하고_잘라_저장한다() {
        var longKey = "k".repeat(150);
        var value = """
                {"eventId": "%s", "eventType": "Bad\\u0000Type", "version": 1, "occurredAt": "2026-11-03T01:00:02Z",
                 "producer": "payment-service", "traceId": null,
                 "payload": {"paymentId": 1, "reservationId": 1, "userId": 1}}""".formatted(UUID.randomUUID());

        listener.onMessage(dlqRecord(longKey, value));

        // JSON의 \u0000 이스케이프는 읽으면 실제 NUL이 되어 파서 오류 메시지(사유)에 들어간다
        var row = row(SOURCE_TOPIC + "-0-" + offset);
        assertEquals("OPEN", row.get("status"));
        assertEquals(100, ((String) row.get("message_key")).length());
        assertFalse(((String) row.get("reason")).contains(String.valueOf((char) 0)));
        assertTrue(((String) row.get("reason")).contains("Bad" + (char) 0xFFFD + "Type"));
    }

    @Test
    void 원문의_NUL_문자도_치환해_저장한다() {
        listener.onMessage(dlqRecord("x" + (char) 0 + "y"));

        assertEquals("x" + (char) 0xFFFD + "y", row(SOURCE_TOPIC + "-0-" + offset).get("payload_raw"));
    }

    @Test
    void 먼저_OPEN으로_남은_이벤트가_나중에_다시_처리되면_RESOLVED로_닫는다() {
        var eventId = UUID.randomUUID();
        doThrow(new IllegalStateException("처리 중에 예매 상태가 바뀌었습니다")).doNothing()
                .when(paymentResults).handle(any());

        listener.onMessage(dlqRecord(approved(eventId)));
        assertEquals("OPEN", row(eventId.toString()).get("status"));
        listener.onMessage(dlqRecord(approved(eventId)));

        var row = row(eventId.toString());
        assertEquals("RESOLVED", row.get("status"));
        assertEquals("REPLAYED", row.get("resolution"));
        assertEquals(1, count());
    }

    private ConsumerRecord<String, String> dlqRecord(String value) {
        return dlqRecord("10", value);
    }

    private ConsumerRecord<String, String> dlqRecord(String key, String value) {
        var record = new ConsumerRecord<>(PaymentResultDlqListener.TOPIC, 0, 7L, key, value);
        record.headers().add(KafkaHeaders.DLT_ORIGINAL_TOPIC, SOURCE_TOPIC.getBytes(StandardCharsets.UTF_8));
        record.headers().add(KafkaHeaders.DLT_ORIGINAL_PARTITION, ByteBuffer.allocate(Integer.BYTES).putInt(0).array());
        record.headers().add(KafkaHeaders.DLT_ORIGINAL_OFFSET, ByteBuffer.allocate(Long.BYTES).putLong(offset).array());
        return record;
    }

    private static String approved(UUID eventId) {
        return """
                {"eventId": "%s", "eventType": "PaymentApproved", "version": 1, "occurredAt": "2026-11-03T01:00:02Z",
                 "producer": "payment-service", "traceId": null,
                 "payload": {"paymentId": 9001, "reservationId": 10, "userId": 42, "amount": 330000, "currency": "KRW",
                             "approvedAt": "2026-11-03T01:00:01Z"}}""".formatted(eventId);
    }

    private Map<String, Object> row(String eventKey) {
        return jdbc.queryForMap("SELECT * FROM dead_letters WHERE event_key = ?", eventKey);
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM dead_letters WHERE source_topic = ? AND source_offset = ?",
                Integer.class, SOURCE_TOPIC, offset);
    }
}
