package com.iseonjwa.payment.payment.application;

import com.iseonjwa.payment.payment.domain.DeclineReason;
import com.iseonjwa.payment.payment.domain.Payment;
import com.iseonjwa.payment.payment.domain.PaymentMethod;
import com.iseonjwa.payment.payment.domain.PaymentRepository;
import com.iseonjwa.payment.payment.domain.PaymentStatus;
import com.iseonjwa.payment.payment.domain.PaymentStatusHistoryRepository;
import com.iseonjwa.payment.payment.domain.StatusActor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
@Transactional
class PaymentTransitionServiceTest {
    private static final BigDecimal AMOUNT = BigDecimal.valueOf(50000);
    private static final AtomicLong RESERVATION_IDS = new AtomicLong(2_000_000);

    @Autowired
    PaymentTransitionService transitions;
    @Autowired
    PaymentRepository payments;
    @Autowired
    PaymentStatusHistoryRepository histories;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 승인_전이는_상태와_PG_정보를_바꾸고_이력을_남긴다() {
        var payment = requested();

        assertTrue(transitions.approve(payment.getId(), PaymentStatus.REQUESTED, "pg-tx-approve", AMOUNT, Instant.now(),
                StatusActor.SYSTEM));

        var approved = payments.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.APPROVED, approved.getStatus());
        assertEquals("pg-tx-approve", approved.getPgTxId());
        assertEquals(0, AMOUNT.compareTo(approved.getPgApprovedAmount()));
        assertNotNull(approved.getApprovedAt());
        assertNull(approved.getNextCheckAt(), "종결 상태는 재확인 대상이 아니다");
        var history = histories.findByPaymentId(payment.getId());
        assertEquals(1, history.size());
        assertEquals(PaymentStatus.REQUESTED, history.get(0).getFromStatus());
        assertEquals(PaymentStatus.APPROVED, history.get(0).getToStatus());
        assertEquals("SYSTEM", history.get(0).getActor());
    }

    @Test
    void 이미_바뀐_결제를_같은_상태_기준으로_다시_전이하면_이력을_쓰지_않는다() {
        var payment = requested();
        assertTrue(transitions.approve(payment.getId(), PaymentStatus.REQUESTED, "pg-tx-first", AMOUNT, Instant.now(),
                StatusActor.SYSTEM));

        assertFalse(transitions.decline(payment.getId(), PaymentStatus.REQUESTED, DeclineReason.OTHER,
                StatusActor.SYSTEM));

        assertEquals(PaymentStatus.APPROVED, payments.findById(payment.getId()).orElseThrow().getStatus());
        assertEquals(1, histories.findByPaymentId(payment.getId()).size(), "영향 행이 0이면 이력도 없다(INV-5)");
    }

    @Test
    void 승인하면_같은_트랜잭션에서_PaymentApproved를_Outbox에_쓴다() {
        var payment = requested();

        transitions.approve(payment.getId(), PaymentStatus.REQUESTED, "pg-tx-outbox", AMOUNT, Instant.now(), StatusActor.SYSTEM);

        var rows = outboxRows(payment.getReservationId());
        assertEquals(1, rows.size());
        assertEquals("PaymentApproved", rows.get(0).get("event_type"));
        assertEquals("payment.result", rows.get(0).get("topic"));
        assertEquals("PENDING", rows.get(0).get("status"));
        assertEquals(String.valueOf(payment.getId()), payloadField(payment.getReservationId(), "paymentId"));
        assertEquals("50000", payloadField(payment.getReservationId(), "amount"));
        assertEquals("KRW", payloadField(payment.getReservationId(), "currency"));
    }

    @Test
    void 거절하면_PaymentDeclined를_Outbox에_쓴다() {
        var payment = requested();

        transitions.decline(payment.getId(), PaymentStatus.REQUESTED, DeclineReason.TOKEN_EXPIRED, StatusActor.SYSTEM);

        var rows = outboxRows(payment.getReservationId());
        assertEquals(1, rows.size());
        assertEquals("PaymentDeclined", rows.get(0).get("event_type"));
        assertEquals("TOKEN_EXPIRED", payloadField(payment.getReservationId(), "reasonCode"));
    }

    @Test
    void 영향_행이_0이면_Outbox에도_쓰지_않는다() {
        var payment = requested();
        transitions.approve(payment.getId(), PaymentStatus.REQUESTED, "pg-tx-first", AMOUNT, Instant.now(), StatusActor.SYSTEM);

        transitions.decline(payment.getId(), PaymentStatus.REQUESTED, DeclineReason.OTHER, StatusActor.SYSTEM);

        assertEquals(1, outboxRows(payment.getReservationId()).size(), "승인 이벤트 하나뿐이다");
    }

    @Test
    void 확인_필요로_바뀌는_전이는_이벤트를_내지_않는다() {
        var payment = requested();

        transitions.markUnresolved(payment.getId(), PaymentStatus.REQUESTED, StatusActor.SYSTEM);

        assertEquals(0, outboxRows(payment.getReservationId()).size());
    }

    @Test
    void 거절은_사유를_저장한다() {
        var payment = requested();

        assertTrue(transitions.decline(payment.getId(), PaymentStatus.REQUESTED, DeclineReason.TOKEN_EXPIRED,
                StatusActor.SYSTEM));

        var declined = payments.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.DECLINED, declined.getStatus());
        assertEquals(DeclineReason.TOKEN_EXPIRED, declined.getDeclineCode());
        assertEquals("TOKEN_EXPIRED", histories.findByPaymentId(payment.getId()).get(0).getReason());
    }

    @Test
    void 불확실한_응답은_UNKNOWN으로_두고_다음_조회_시각을_남긴다() {
        var payment = requested();
        var nextCheckAt = Instant.parse("2026-10-28T03:00:30Z");

        assertTrue(transitions.markUnknown(payment.getId(), PaymentStatus.REQUESTED, nextCheckAt, "TIMEOUT",
                StatusActor.SYSTEM));

        var unknown = payments.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.UNKNOWN, unknown.getStatus());
        assertEquals(nextCheckAt, unknown.getNextCheckAt());
    }

    @Test
    void 금액_불일치는_확인_필요로_두고_PG_정보를_보존한다() {
        var payment = requested();

        assertTrue(transitions.markAmountMismatch(payment.getId(), PaymentStatus.REQUESTED, "pg-tx-mismatch",
                BigDecimal.valueOf(49000), StatusActor.SYSTEM));

        var checked = payments.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.NEEDS_MANUAL_CHECK, checked.getStatus());
        assertEquals("pg-tx-mismatch", checked.getPgTxId());
        assertEquals(0, BigDecimal.valueOf(49000).compareTo(checked.getPgApprovedAmount()));
        assertNotNull(checked.getApprovedAt());
    }

    @Test
    void 재조회_소진은_PG_정보와_승인_시각을_남기지_않는다() {
        var payment = requested();
        assertTrue(transitions.markUnknown(payment.getId(), PaymentStatus.REQUESTED, Instant.now().plusSeconds(30),
                "TIMEOUT", StatusActor.SYSTEM));

        assertTrue(transitions.markUnresolved(payment.getId(), PaymentStatus.UNKNOWN, StatusActor.SYSTEM));

        var checked = payments.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.NEEDS_MANUAL_CHECK, checked.getStatus());
        assertNull(checked.getPgTxId());
        assertNull(checked.getApprovedAt());
    }

    @Test
    void 허용되지_않는_전이는_저장소를_부르기_전에_거절한다() {
        var payment = requested();
        assertTrue(transitions.decline(payment.getId(), PaymentStatus.REQUESTED, DeclineReason.OTHER,
                StatusActor.SYSTEM));

        assertThrows(IllegalStateException.class, () -> transitions.approve(payment.getId(), PaymentStatus.DECLINED,
                "pg-tx-late", AMOUNT, Instant.now(), StatusActor.SYSTEM));
    }

    @Test
    void 결제_금액과_다른_승인_금액은_DB_제약이_막는다() {
        var payment = requested();

        assertThrows(DataIntegrityViolationException.class, () -> transitions.approve(payment.getId(),
                PaymentStatus.REQUESTED, "pg-tx-wrong", BigDecimal.valueOf(49000), Instant.now(), StatusActor.SYSTEM));
    }

    @Test
    void 운영자_전이는_이력의_행위자에_OPS를_남긴다() {
        var payment = requested();
        assertTrue(transitions.markUnresolved(payment.getId(), PaymentStatus.REQUESTED, StatusActor.SYSTEM));

        assertTrue(transitions.approve(payment.getId(), PaymentStatus.NEEDS_MANUAL_CHECK, "pg-tx-ops", AMOUNT, Instant.now(),
                StatusActor.ops("kim")));

        var history = histories.findByPaymentId(payment.getId());
        assertEquals("OPS:kim", history.get(1).getActor());
    }

    private java.util.List<java.util.Map<String, Object>> outboxRows(Long reservationId) {
        return jdbc.queryForList("SELECT event_type, topic, status FROM outbox WHERE message_key = ? ORDER BY id",
                String.valueOf(reservationId));
    }

    // 봉투 안 payload 객체의 필드 값을 문자열로 읽는다
    private String payloadField(Long reservationId, String field) {
        return jdbc.queryForObject("SELECT payload -> 'payload' ->> ? FROM outbox WHERE message_key = ?",
                String.class, field, String.valueOf(reservationId));
    }

    private Payment requested() {
        var reservationId = RESERVATION_IDS.incrementAndGet();
        return payments.save(Payment.request("PAY-" + reservationId, reservationId, 1L, AMOUNT, PaymentMethod.CARD,
                UUID.randomUUID(), Instant.now()));
    }
}
