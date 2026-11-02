package com.iseonjwa.reservation.global;

import com.iseonjwa.common.messaging.outbox.OutboxWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// 예매 서비스 outbox(ISE-147). 업무 변경과 이벤트 행이 같은 트랜잭션으로 커밋·롤백되는지 확인한다.
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class ReservationOutboxTest {
    private static final String TOPIC = "payment.command";
    private static final String MARK = "outbox 테스트";

    @Autowired
    OutboxWriter outboxWriter;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcTemplate jdbc;

    private final String messageKey = "outbox-test-" + UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM outbox_events WHERE message_key = ?", messageKey);
        jdbc.update("DELETE FROM venues WHERE name = ?", MARK);
    }

    // 완료 기준: 커밋되면 업무 변경과 PENDING 행이 함께 남는다
    @Test
    void 커밋하면_업무_변경과_PENDING_이벤트가_함께_남는다() {
        UUID eventId = tx.execute(status -> {
            insertVenue();
            return outboxWriter.write(TOPIC, messageKey, "BillingPaymentRequested", 1, Map.of("reservationId", 1L));
        });

        var row = jdbc.queryForMap("SELECT event_id, topic, event_type, status, attempts FROM outbox_events"
                + " WHERE message_key = ?", messageKey);
        assertEquals(eventId, row.get("event_id"));
        assertEquals(TOPIC, row.get("topic"));
        assertEquals("BillingPaymentRequested", row.get("event_type"));
        assertEquals("PENDING", row.get("status"));
        assertEquals(0, ((Number) row.get("attempts")).intValue());
        assertEquals(1, venueCount());
        assertEquals("reservation-service", jdbc.queryForObject("SELECT payload ->> 'producer' FROM outbox_events"
                + " WHERE message_key = ?", String.class, messageKey));
    }

    // 완료 기준: 업무 트랜잭션이 롤백되면 이벤트 행도 남지 않는다
    @Test
    void 롤백하면_업무_변경과_이벤트가_함께_사라진다() {
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            insertVenue();
            outboxWriter.write(TOPIC, messageKey, "BillingPaymentRequested", 1, Map.of("reservationId", 1L));
            throw new IllegalStateException("업무 실패");
        }));

        assertEquals(0, outboxCount());
        assertEquals(0, venueCount());
    }

    // 트랜잭션 밖에서 쓰면 이벤트만 커밋되는 일이 생기므로 공통 모듈이 거절한다
    @Test
    void 트랜잭션_밖에서_쓰면_거절한다() {
        assertThrows(IllegalTransactionStateException.class,
                () -> outboxWriter.write(TOPIC, messageKey, "BillingPaymentRequested", 1, Map.of()));

        assertEquals(0, outboxCount());
    }

    private void insertVenue() {
        jdbc.update("INSERT INTO venues (name, created_at, updated_at) VALUES (?, now(), now())", MARK);
    }

    private int venueCount() {
        return jdbc.queryForObject("SELECT count(*) FROM venues WHERE name = ?", Integer.class, MARK);
    }

    private int outboxCount() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE message_key = ?", Integer.class,
                messageKey);
    }
}
