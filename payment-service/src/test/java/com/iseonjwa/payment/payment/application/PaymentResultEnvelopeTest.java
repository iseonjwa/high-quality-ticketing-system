package com.iseonjwa.payment.payment.application;

import com.iseonjwa.payment.payment.domain.DeclineReason;
import com.iseonjwa.payment.payment.domain.Payment;
import com.iseonjwa.payment.payment.domain.PaymentMethod;
import com.iseonjwa.payment.payment.domain.PaymentRepository;
import com.iseonjwa.payment.payment.domain.PaymentStatus;
import com.iseonjwa.payment.payment.domain.StatusActor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 결제 결과 이벤트의 외부 계약(SA 10-2·10-3, S-18)을 JSON 모양으로 고정한다. 소비자인 Reservation이 의존한다.
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
@Transactional
class PaymentResultEnvelopeTest {
    private static final BigDecimal AMOUNT = BigDecimal.valueOf(50000);
    private static final AtomicLong RESERVATION_IDS = new AtomicLong(7_000_000);
    private static final Set<String> ENVELOPE_KEYS = Set.of("eventId", "eventType", "version", "occurredAt", "producer",
            "traceId", "payload");

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
        var payment = requested();
        var pgApprovedAt = Instant.parse("2026-11-02T11:00:01.123456Z");
        transitions.approve(payment.getId(), PaymentStatus.REQUESTED, "pg-tx-env", AMOUNT, pgApprovedAt,
                StatusActor.SYSTEM);

        var json = envelope(payment);

        assertEquals(ENVELOPE_KEYS, keys(json));
        assertNotNull(UUID.fromString(json.get("eventId").asString()));
        assertEquals("PaymentApproved", json.get("eventType").asString());
        assertEquals(1, json.get("version").asInt());
        assertEquals("payment-service", json.get("producer").asString());
        assertTrue(json.get("traceId").isNull(), "추적 ID가 없으면 키는 있고 값은 null이다");
        assertNotNull(Instant.parse(json.get("occurredAt").asString()));
        var body = json.get("payload");
        assertEquals(Set.of("paymentId", "reservationId", "userId", "amount", "currency", "approvedAt"), keys(body));
        assertEquals(payment.getId(), body.get("paymentId").asLong());
        assertEquals(payment.getReservationId(), body.get("reservationId").asLong());
        assertEquals(42L, body.get("userId").asLong());
        assertTrue(body.get("amount").isNumber());
        assertEquals(50000, body.get("amount").asInt());
        assertEquals("KRW", body.get("currency").asString());
    }

    @Test
    void 승인_시각은_확정한_시각이_아니라_PG가_승인한_시각이고_밀리초_정밀도다() {
        var payment = requested();
        var pgApprovedAt = Instant.parse("2026-11-02T11:00:01.123456Z");
        transitions.approve(payment.getId(), PaymentStatus.REQUESTED, "pg-tx-env", AMOUNT, pgApprovedAt,
                StatusActor.SYSTEM);

        var body = envelope(payment).get("payload");

        assertEquals("2026-11-02T11:00:01.123Z", body.get("approvedAt").asString());
        assertEquals(pgApprovedAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                payments.findById(payment.getId()).orElseThrow().getApprovedAt(), "DB에도 PG 승인 시각이 저장된다");
    }

    @Test
    void PaymentDeclined_봉투와_페이로드_계약() {
        var payment = requested();
        transitions.decline(payment.getId(), PaymentStatus.REQUESTED, DeclineReason.CARD_DECLINED, StatusActor.SYSTEM);

        var json = envelope(payment);

        assertEquals(ENVELOPE_KEYS, keys(json));
        assertEquals("PaymentDeclined", json.get("eventType").asString());
        assertEquals(Set.of("paymentId", "reservationId", "userId", "reasonCode"), keys(json.get("payload")));
        assertEquals("CARD_DECLINED", json.get("payload").get("reasonCode").asString());
    }

    @Test
    void 이벤트_ID는_Outbox_행의_event_id와_같다() {
        var payment = requested();
        transitions.approve(payment.getId(), PaymentStatus.REQUESTED, "pg-tx-env2", AMOUNT, Instant.now(),
                StatusActor.SYSTEM);

        var eventId = jdbc.queryForObject("SELECT event_id::text FROM outbox WHERE message_key = ?", String.class,
                String.valueOf(payment.getReservationId()));

        assertEquals(eventId, envelope(payment).get("eventId").asString());
    }

    private JsonNode envelope(Payment payment) {
        return mapper.readTree(jdbc.queryForObject("SELECT payload::text FROM outbox WHERE message_key = ?",
                String.class, String.valueOf(payment.getReservationId())));
    }

    private static Set<String> keys(JsonNode node) {
        var keys = new HashSet<String>();
        node.propertyNames().forEach(keys::add);
        return keys;
    }

    private Payment requested() {
        var reservationId = RESERVATION_IDS.incrementAndGet();
        return payments.save(Payment.request("PAY-ENV-" + reservationId, reservationId, 42L, AMOUNT,
                PaymentMethod.CARD, UUID.randomUUID(), Instant.now()));
    }
}
