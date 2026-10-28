package com.iseonjwa.payment.event.application;

import com.iseonjwa.payment.payment.domain.Payment;
import com.iseonjwa.payment.payment.domain.PaymentMethod;
import com.iseonjwa.payment.payment.domain.PaymentRepository;
import com.iseonjwa.payment.payment.domain.PaymentStatus;
import com.iseonjwa.payment.payment.domain.StatusActor;
import com.iseonjwa.payment.payment.application.PaymentTransitionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 결제 결과 이벤트의 외부 계약(SA 10-2·10-3, S-18)을 JSON 모양으로 고정한다. 소비자인 Reservation이 의존한다.
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
@Transactional
class PaymentResultEnvelopeTest {
    @Autowired
    PaymentTransitionService transitions;
    @Autowired
    PaymentRepository payments;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper mapper;

    @Test
    void PaymentApproved_봉투와_페이로드_계약() {
        var payment = payments.save(Payment.request("PAY-ENV-1", 7_000_001L, 42L, BigDecimal.valueOf(50000),
                PaymentMethod.CARD, UUID.randomUUID(), Instant.now()));
        transitions.approve(payment.getId(), PaymentStatus.REQUESTED, "pg-tx-env", BigDecimal.valueOf(50000),
                StatusActor.SYSTEM);

        var json = mapper.readTree(jdbc.queryForObject("SELECT payload::text FROM outbox WHERE message_key = '7000001'",
                String.class));

        assertNotNull(UUID.fromString(json.get("eventId").asString()));
        assertEquals("PaymentApproved", json.get("eventType").asString());
        assertEquals(1, json.get("version").asInt());
        assertEquals("payment-service", json.get("producer").asString());
        assertNotNull(Instant.parse(json.get("occurredAt").asString()));
        var body = json.get("payload");
        assertEquals(payment.getId(), body.get("paymentId").asLong());
        assertEquals(7_000_001L, body.get("reservationId").asLong());
        assertEquals(42L, body.get("userId").asLong());
        assertTrue(body.get("amount").isNumber());
        assertEquals(50000, body.get("amount").asInt());
        assertEquals("KRW", body.get("currency").asString());
        assertNotNull(Instant.parse(body.get("approvedAt").asString()));
    }

    @Test
    void 이벤트_ID는_Outbox_행의_event_id와_같다() {
        var payment = payments.save(Payment.request("PAY-ENV-2", 7_000_002L, 42L, BigDecimal.valueOf(50000),
                PaymentMethod.CARD, UUID.randomUUID(), Instant.now()));
        transitions.approve(payment.getId(), PaymentStatus.REQUESTED, "pg-tx-env2", BigDecimal.valueOf(50000),
                StatusActor.SYSTEM);

        var row = jdbc.queryForMap("SELECT event_id::text AS event_id, payload::text AS payload FROM outbox "
                + "WHERE message_key = '7000002'");

        assertEquals(row.get("event_id"), mapper.readTree(row.get("payload").toString()).get("eventId").asString());
    }
}
