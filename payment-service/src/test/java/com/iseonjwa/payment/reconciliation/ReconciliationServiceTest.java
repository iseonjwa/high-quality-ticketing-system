package com.iseonjwa.payment.reconciliation;

import com.iseonjwa.payment.observability.DbAggregateGauges;
import com.iseonjwa.payment.reconciliation.application.ReconciliationService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 대사 기록 규칙과 RC-7·RC-8을 PostgreSQL에서 확인한다(ISE-167). 테스트는 트랜잭션 안에서 다른 테스트의 행까지 비운 뒤 행을 넣고
 * 끝나면 롤백한다. 기준 시각을 서비스에 인자로 넘겨 {@code sleep} 없이 허용 지연(10분)을 넘긴다.
 */
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
@Transactional
class ReconciliationServiceTest {
    private static final Instant CREATED = Instant.parse("2026-11-03T00:00:00Z");
    // 마지막 갱신(CREATED)에서 허용 지연 10분이 지난 시각
    private static final Instant AFTER_LAG = CREATED.plus(Duration.ofMinutes(11));

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ReconciliationService service;
    @Autowired
    DbAggregateGauges gauges;
    @Autowired
    MeterRegistry meters;

    private ReconciliationFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ReconciliationFixtures(jdbc);
        fixtures.wipe();
    }

    // ----- 기록 규칙 (RC-7을 수단으로 쓴다)

    @Test
    void 새_불일치는_OPEN으로_기록한다() {
        var payment = tamperedPayment();

        service.runOnce(AFTER_LAG);

        assertEquals(1, open("RC-7", payment));
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM reconciliation_mismatches WHERE payment_id = ?",
                String.class, payment));
    }

    @Test
    void 다음_회차에도_있으면_같은_행의_last_seen_at만_갱신한다() {
        var payment = tamperedPayment();
        service.runOnce(AFTER_LAG);
        var first = row(payment);

        service.runOnce(AFTER_LAG.plus(Duration.ofMinutes(5)));

        assertEquals(1, count("SELECT count(*) FROM reconciliation_mismatches WHERE payment_id = ?", payment));
        var second = row(payment);
        assertEquals(first.get("id"), second.get("id"));
        assertEquals(first.get("first_detected_at"), second.get("first_detected_at"));
        assertTrue(((java.sql.Timestamp) second.get("last_seen_at")).after((java.sql.Timestamp) first.get("last_seen_at")));
    }

    @Test
    void 사라지면_RESOLVED로_닫는다() {
        var payment = tamperedPayment();
        service.runOnce(AFTER_LAG);

        jdbc.update("UPDATE payments SET status = 'APPROVED' WHERE id = ?", payment);
        service.runOnce(AFTER_LAG.plus(Duration.ofMinutes(5)));

        var row = row(payment);
        assertEquals("RESOLVED", row.get("status"));
        assertNotNull(row.get("resolved_at"));
    }

    @Test
    void 닫힌_뒤_다시_어긋나면_새_OPEN_행을_만든다() {
        var payment = tamperedPayment();
        service.runOnce(AFTER_LAG);
        jdbc.update("UPDATE payments SET status = 'APPROVED' WHERE id = ?", payment);
        service.runOnce(AFTER_LAG.plus(Duration.ofMinutes(5)));

        jdbc.update("UPDATE payments SET status = 'REFUNDED' WHERE id = ?", payment);
        service.runOnce(AFTER_LAG.plus(Duration.ofMinutes(10)));

        assertEquals(2, count("SELECT count(*) FROM reconciliation_mismatches WHERE payment_id = ?", payment));
        assertEquals(1, open("RC-7", payment));
    }

    @Test
    void 허용_지연_안이면_기록도_닫기도_하지_않는다() {
        var recent = fixtures.payment(fixtures.newReservation(), "REFUNDED", CREATED);
        fixtures.paymentHistory(recent, "REQUESTED", "APPROVED", CREATED);
        fixtures.outbox(reservationOf(recent), recent, "PaymentApproved", CREATED);

        service.runOnce(CREATED.plus(Duration.ofMinutes(9)));

        assertEquals(0, count("SELECT count(*) FROM reconciliation_mismatches"), "L이 지나기 전에는 기록하지 않는다");

        // 이미 OPEN인 대상이 다시 갱신되면(L 안) 닫지도 않는다
        service.runOnce(AFTER_LAG);
        assertEquals(1, open("RC-7", recent));
        jdbc.update("UPDATE payments SET status = 'APPROVED', updated_at = ? WHERE id = ?",
                java.sql.Timestamp.from(AFTER_LAG.minus(Duration.ofMinutes(1))), recent);
        service.runOnce(AFTER_LAG);

        assertEquals("OPEN", row(recent).get("status"), "맞게 고쳐졌어도 마지막 갱신이 L 안이면 이번 회차는 판단하지 않는다");
        service.runOnce(AFTER_LAG.plus(Duration.ofMinutes(10)));
        assertEquals("RESOLVED", row(recent).get("status"));
    }

    @Test
    void 기간_밖으로_나간_OPEN_대상도_다시_판단해_닫는다() {
        var payment = tamperedPayment();
        service.runOnce(AFTER_LAG);
        assertEquals(1, open("RC-7", payment));

        // 7일 기간이 지나도 OPEN 행이 가리키는 결제는 계속 판단한다
        var afterWindow = CREATED.plus(Duration.ofDays(8));
        service.runOnce(afterWindow);
        assertEquals("OPEN", row(payment).get("status"), "기간 밖이라는 이유로 닫지 않는다");

        jdbc.update("UPDATE payments SET status = 'APPROVED' WHERE id = ?", payment);
        service.runOnce(afterWindow.plus(Duration.ofMinutes(5)));
        assertEquals("RESOLVED", row(payment).get("status"));
    }

    @Test
    void 기간_밖의_오래된_결제는_판단하지_않는다() {
        var old = fixtures.payment(fixtures.newReservation(), "REFUNDED", CREATED);
        fixtures.paymentHistory(old, "REQUESTED", "APPROVED", CREATED);

        service.runOnce(CREATED.plus(Duration.ofDays(8)));

        assertEquals(0, count("SELECT count(*) FROM reconciliation_mismatches"));
    }

    // ----- RC-7

    @Test
    void N30_직접_SQL로_바꾼_결제_상태를_L_뒤에_RC7로_잡고_맞추면_닫는다() {
        var payment = tamperedPayment();

        service.runOnce(AFTER_LAG);
        gauges.refresh();
        assertEquals(1, gauge("RC-7"));

        jdbc.update("UPDATE payments SET status = 'APPROVED' WHERE id = ?", payment);
        service.runOnce(AFTER_LAG.plus(Duration.ofMinutes(5)));
        gauges.refresh();
        assertEquals(0, gauge("RC-7"));
        assertEquals("RESOLVED", row(payment).get("status"));
    }

    @Test
    void RC7_환불_쪽_어긋남도_잡고_결제와_함께_한_행에_담는다() {
        var payment = fixtures.payment(fixtures.newReservation(), "APPROVED", CREATED);
        fixtures.paymentHistory(payment, "REQUESTED", "APPROVED", CREATED);
        fixtures.outbox(reservationOf(payment), payment, "PaymentApproved", CREATED);
        fixtures.refund(payment, "REFUNDED", CREATED);
        fixtures.history(payment, "REFUND", null, "REQUESTED", CREATED);

        service.runOnce(AFTER_LAG);

        assertEquals(1, open("RC-7", payment));
        var detail = jdbc.queryForObject("SELECT detail::text FROM reconciliation_mismatches WHERE payment_id = ?",
                String.class, payment);
        assertTrue(detail.contains("\"refund\""), detail);
        assertTrue(!detail.contains("\"payment\""), "결제는 맞다: " + detail);
    }

    @Test
    void RC7_이력과_맞는_결제는_이력이_없는_REQUESTED_결제도_불일치가_아니다() {
        var requested = fixtures.payment(fixtures.newReservation(), "REQUESTED", CREATED);
        var approved = fixtures.payment(fixtures.newReservation(), "APPROVED", CREATED);
        fixtures.paymentHistory(approved, "REQUESTED", "APPROVED", CREATED);
        fixtures.outbox(reservationOf(approved), approved, "PaymentApproved", CREATED);

        service.runOnce(AFTER_LAG);

        assertEquals(0, count("SELECT count(*) FROM reconciliation_mismatches"), "requested=" + requested);
    }

    // ----- RC-8

    @Test
    void 확정_전이의_Outbox_행이_없으면_RC8로_잡는다() {
        var approved = confirmed("APPROVED", "PaymentApproved");
        var declined = confirmed("DECLINED", "PaymentDeclined");
        var refunded = confirmed("REFUNDED", "PaymentRefunded");
        jdbc.update("DELETE FROM outbox_events WHERE message_key = ?", String.valueOf(reservationOf(approved)));

        service.runOnce(AFTER_LAG);

        assertEquals(1, open("RC-8", approved));
        assertEquals(0, open("RC-8", declined));
        assertEquals(0, open("RC-8", refunded));
        gauges.refresh();
        assertEquals(1, gauge("RC-8"));
    }

    @Test
    void RC8_다른_결제의_같은_이벤트_종류는_대응으로_치지_않는다() {
        var first = confirmed("APPROVED", "PaymentApproved");
        var second = confirmed("APPROVED", "PaymentApproved");
        // 두 번째 결제의 행을 지우고 첫 번째 결제 ID로 바꿔 놓는다(예매 키는 두 번째 그대로)
        jdbc.update("""
                UPDATE outbox_events SET payload = jsonb_set(payload, '{payload,paymentId}', to_jsonb(?::bigint))
                WHERE message_key = ?""", first, String.valueOf(reservationOf(second)));

        service.runOnce(AFTER_LAG);

        assertEquals(1, open("RC-8", second));
        assertEquals(0, open("RC-8", first));
    }

    @Test
    void RC8_행이_다시_생기면_닫는다() {
        var approved = confirmed("APPROVED", "PaymentApproved");
        jdbc.update("DELETE FROM outbox_events WHERE message_key = ?", String.valueOf(reservationOf(approved)));
        service.runOnce(AFTER_LAG);
        assertEquals(1, open("RC-8", approved));

        fixtures.outbox(reservationOf(approved), approved, "PaymentApproved", CREATED);
        service.runOnce(AFTER_LAG.plus(Duration.ofMinutes(5)));

        assertEquals("RESOLVED", row(approved).get("status"));
    }

    @Test
    void RC8_전이가_허용_지연_안이면_판단하지_않는다() {
        var approved = confirmed("APPROVED", "PaymentApproved");
        jdbc.update("DELETE FROM outbox_events");

        service.runOnce(CREATED.plus(Duration.ofMinutes(9)));

        assertEquals(0, count("SELECT count(*) FROM reconciliation_mismatches"), "approved=" + approved);
    }

    @Test
    void RC8_24시간_밖의_전이는_판단하지_않는다() {
        var old = confirmed("APPROVED", "PaymentApproved");
        jdbc.update("DELETE FROM outbox_events");

        service.runOnce(CREATED.plus(Duration.ofHours(25)));

        assertEquals(0, open("RC-8", old));
    }

    @Test
    void 정상_흐름_승인_거절_환불은_RC7_RC8_불일치가_없다() {
        confirmed("APPROVED", "PaymentApproved");
        confirmed("DECLINED", "PaymentDeclined");
        confirmed("REFUNDED", "PaymentRefunded");

        service.runOnce(AFTER_LAG);

        assertEquals(0, count("SELECT count(*) FROM reconciliation_mismatches"));
    }

    @Test
    void 규칙_여덟_개의_Gauge는_행이_없어도_0으로_있다() {
        gauges.refresh();

        for (var rule : new String[] {"RC-1", "RC-2", "RC-3", "RC-4", "RC-5", "RC-6", "RC-7", "RC-8"}) {
            assertEquals(0, gauge(rule), rule);
        }
    }

    // ----- 도구

    /** 이력은 APPROVED인데 결제 상태만 직접 REFUNDED로 바꾼 결제. */
    private long tamperedPayment() {
        var payment = fixtures.payment(fixtures.newReservation(), "APPROVED", CREATED);
        fixtures.paymentHistory(payment, "REQUESTED", "APPROVED", CREATED);
        fixtures.outbox(reservationOf(payment), payment, "PaymentApproved", CREATED);
        jdbc.update("UPDATE payments SET status = 'REFUNDED' WHERE id = ?", payment);
        return payment;
    }

    /** 결제, 확정 전이 이력, 대응하는 Outbox 행이 모두 있는 정상 결제. */
    private long confirmed(String status, String eventType) {
        var payment = fixtures.payment(fixtures.newReservation(), status, CREATED);
        fixtures.paymentHistory(payment, "REQUESTED", status, CREATED);
        fixtures.outbox(reservationOf(payment), payment, eventType, CREATED);
        return payment;
    }

    private long reservationOf(long paymentId) {
        return jdbc.queryForObject("SELECT reservation_id FROM payments WHERE id = ?", Long.class, paymentId);
    }

    private int open(String rule, long paymentId) {
        return count("SELECT count(*) FROM reconciliation_mismatches WHERE rule = ? AND payment_id = ? AND status = 'OPEN'",
                rule, paymentId);
    }

    private java.util.Map<String, Object> row(long paymentId) {
        return jdbc.queryForMap("SELECT * FROM reconciliation_mismatches WHERE payment_id = ? ORDER BY id DESC LIMIT 1",
                paymentId);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private double gauge(String rule) {
        return meters.find("reconciliation.mismatch.open").tag("rule", rule).gauge().value();
    }
}
