package com.iseonjwa.payment.ops;

import com.iseonjwa.payment.ops.application.InspectQuery;
import com.iseonjwa.payment.ops.application.OpsArguments;
import com.iseonjwa.payment.ops.application.OpsRejection;
import com.iseonjwa.payment.ops.application.OpsUsageException;
import com.iseonjwa.payment.ops.domain.OpsInspectionRepository;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgClient.Approved;
import com.iseonjwa.payment.pg.application.PgClient.Declined;
import com.iseonjwa.payment.pg.application.PgClient.NotFound;
import com.iseonjwa.payment.pg.application.PgClient.Refunded;
import com.iseonjwa.payment.pg.application.PgClient.Unknown;
import com.iseonjwa.payment.pg.application.PgPaymentRef;
import com.iseonjwa.payment.pg.application.UnknownCause;
import com.iseonjwa.payment.payment.domain.DeclineReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * inspect가 결제·이력·환불·같은 예매 키의 Outbox와 dead_letters·감사 기록을 묶어 보이고, --with-pg로 PG와 대조하는지
 * 확인한다. 읽기 전용이라 테스트 트랜잭션 안에서 행을 넣고 롤백한다.
 */
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
@Transactional
class InspectQueryTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired OpsInspectionRepository inspection;

    final ScriptedPgClient pg = new ScriptedPgClient();
    OpsFixtures fixtures;
    InspectQuery inspect;

    @BeforeEach
    void setUp() {
        fixtures = new OpsFixtures(jdbc);
        inspect = new InspectQuery(inspection, provider(pg));
    }

    @Test
    void 결제_하나의_상태와_이력과_환불과_Outbox와_DLQ와_감사_기록을_묶어_보인다() {
        var reservation = OpsFixtures.nextReservation();
        var paymentId = fixtures.approvedPayment(reservation);
        fixtures.history(paymentId, "PAYMENT", null, "REQUESTED", "SYSTEM");
        fixtures.history(paymentId, "PAYMENT", "REQUESTED", "APPROVED", "OPS:alice");
        var refundId = fixtures.refund(paymentId, "REQUESTED");
        fixtures.history(paymentId, "REFUND", null, "REQUESTED", "SYSTEM");
        var outboxId = fixtures.outbox(String.valueOf(reservation), "PaymentApproved", "FAILED", 5, "직렬화 실패");
        fixtures.outbox("다른 키", "PaymentApproved", "PENDING", 0, null);
        fixtures.deadLetter(String.valueOf(reservation), "{\"eventId\":\"x\",\"paymentId\":1}");
        jdbc.update("""
                INSERT INTO manual_action_logs (command, target_type, target_id, from_status, to_status, result,
                                                plan_hash, operator, reason, performed_at)
                VALUES ('outbox requeue', 'OUTBOX', ?, 'FAILED', 'PENDING', 'APPLIED', repeat('a', 64), 'alice',
                        '원인을 고쳤다', now())""", outboxId);

        var output = String.join("\n", inspect.report(args("payment-id=" + paymentId)));

        assertTrue(output.contains("예매 키: " + reservation), output);
        assertTrue(output.contains("[결제] id=" + paymentId), output);
        assertTrue(output.contains("상태=APPROVED 금액=50000 KRW"), output);
        assertTrue(output.contains("[상태 이력] 3건"), output);
        assertTrue(output.contains("REQUESTED -> APPROVED") && output.contains("actor=OPS:alice"), output);
        assertTrue(output.contains("[환불] id=" + refundId + " 상태=REQUESTED"), output);
        assertTrue(output.contains("[Outbox] 1건"), output);
        assertTrue(output.contains("상태=FAILED 시도=5") && output.contains("오류=직렬화 실패"), output);
        assertTrue(output.contains("[dead_letters] 1건") && output.contains("원문 앞부분: {\"eventId\":\"x\""), output);
        assertTrue(output.contains("[Ops 기록] 1건") && output.contains("outbox requeue OUTBOX " + outboxId + " APPLIED"),
                output);
        assertTrue(output.contains("[대사 불일치]"), output);
        assertFalse(output.contains("[PG 대조]"), "--with-pg가 없으면 PG를 조회하지 않는다");
    }

    @Test
    void 예매_ID와_환불_ID로도_같은_결제를_찾는다() {
        var reservation = OpsFixtures.nextReservation();
        var paymentId = fixtures.approvedPayment(reservation);
        var refundId = fixtures.refund(paymentId, "REQUESTED");

        assertTrue(String.join("\n", inspect.report(args("reservation-id=" + reservation))).contains("[결제] id=" + paymentId));
        assertTrue(String.join("\n", inspect.report(args("refund-id=" + refundId))).contains("[결제] id=" + paymentId));
    }

    @Test
    void 결제가_없어도_예매_키에_dead_letters가_있으면_보인다() {
        var reservation = OpsFixtures.nextReservation();
        fixtures.deadLetter(String.valueOf(reservation), "{}");

        var output = String.join("\n", inspect.report(args("reservation-id=" + reservation)));

        assertTrue(output.contains("[결제] 없음") && output.contains("[dead_letters] 1건"), output);
    }

    @Test
    void 결제와_예매에_걸린_대사_불일치를_OPEN과_최근_RESOLVED만_보인다() {
        var reservation = OpsFixtures.nextReservation();
        var paymentId = fixtures.approvedPayment(reservation);
        var otherReservation = OpsFixtures.nextReservation();
        var otherPaymentId = fixtures.approvedPayment(otherReservation);
        mismatch("RC-7", reservation, paymentId, "OPEN", null, "{\"payment\":{\"currentStatus\":\"REFUNDED\"}}");
        mismatch("RC-8", reservation, paymentId, "RESOLVED", "now() - interval '1 day'", "{}");
        mismatch("RC-8", reservation, paymentId, "RESOLVED", "now() - interval '30 days'", "{}");
        // 결제가 없는 예매 쪽 불일치(RC-2)도 예매 ID로 보인다
        mismatch("RC-2", reservation, null, "OPEN", null, "{}");
        mismatch("RC-7", otherReservation, otherPaymentId, "OPEN", null, "{}");

        var byPayment = String.join("\n", inspect.report(args("payment-id=" + paymentId)));
        var byReservation = String.join("\n", inspect.report(args("reservation-id=" + reservation)));

        for (var output : List.of(byPayment, byReservation)) {
            assertTrue(output.contains("[대사 불일치] 3건"), output);
            assertTrue(output.contains("RC-7 OPEN") && output.contains("currentStatus"), output);
            assertTrue(output.contains("RC-8 RESOLVED") && output.contains("RC-2 OPEN"), output);
            // 30일 전에 닫힌 RESOLVED와 다른 결제의 불일치는 빠져 3건이다
        }
    }

    @Test
    void 대사_불일치가_없으면_0건이다() {
        var paymentId = fixtures.approvedPayment(OpsFixtures.nextReservation());

        var output = String.join("\n", inspect.report(args("payment-id=" + paymentId)));

        assertTrue(output.contains("[대사 불일치] 0건"), output);
    }

    private void mismatch(String rule, long reservationId, Long paymentId, String status, String resolvedAt,
            String detail) {
        jdbc.update("""
                INSERT INTO reconciliation_mismatches (rule, reservation_id, payment_id, detail, status, first_detected_at,
                                                       last_seen_at, resolved_at)
                VALUES (?, ?, ?, CAST(? AS jsonb), ?, now() - interval '2 days', now() - interval '2 days', %s)"""
                .formatted(resolvedAt == null ? "NULL" : resolvedAt), rule, reservationId, paymentId, detail, status);
    }

    @Test
    void 대상이_없으면_거부한다() {
        assertThrows(OpsRejection.class, () -> inspect.report(args("payment-id=999999999")));
        assertThrows(OpsRejection.class, () -> inspect.report(args("refund-id=999999999")));
        assertThrows(OpsRejection.class, () -> inspect.report(args("reservation-id=" + OpsFixtures.nextReservation())));
    }

    @Test
    void 대상을_하나만_지정해야_한다() {
        assertThrows(OpsUsageException.class, () -> inspect.report(args()));
        assertThrows(OpsUsageException.class, () -> inspect.report(args("payment-id=1", "refund-id=1")));
    }

    @Test
    void with_pg는_PG와_DB가_맞으면_일치로_표시한다() {
        var reservation = OpsFixtures.nextReservation();
        var paymentId = fixtures.approvedPayment(reservation);
        fixtures.refund(paymentId, "REFUNDED");
        pg.payment = new Approved(new PgPaymentRef("pg-tx-" + reservation), new BigDecimal("50000"), "KRW", Instant.now());
        pg.refund = new Refunded("ref-1", new BigDecimal("50000.0"), "KRW", Instant.now());

        var output = String.join("\n", inspect.report(args("payment-id=" + paymentId, "with-pg")));

        assertTrue(output.contains("[PG 대조]"), output);
        assertTrue(output.contains("결제: PG=승인") && output.contains("-> 일치"), output);
        assertTrue(output.contains("환불: PG=환불 완료") && output.endsWith("일치"), output);
    }

    @Test
    void with_pg는_금액이_다르면_불일치로_표시한다() {
        var paymentId = fixtures.approvedPayment(OpsFixtures.nextReservation());
        pg.payment = new Approved(new PgPaymentRef("pg-tx"), new BigDecimal("49000"), "KRW", Instant.now());

        var output = String.join("\n", inspect.report(args("payment-id=" + paymentId, "with-pg")));

        assertTrue(output.contains("불일치: 금액·통화가 다르다"), output);
    }

    @Test
    void with_pg는_DB가_승인인데_PG가_거절이면_불일치로_표시한다() {
        var paymentId = fixtures.approvedPayment(OpsFixtures.nextReservation());
        pg.payment = new Declined(DeclineReason.OTHER);

        assertTrue(String.join("\n", inspect.report(args("payment-id=" + paymentId, "with-pg")))
                .contains("불일치: DB가 거절이 아니다"));
    }

    @Test
    void with_pg는_환불_금액이_다르거나_PG에_환불이_없으면_불일치로_표시한다() {
        var reservation = OpsFixtures.nextReservation();
        var paymentId = fixtures.approvedPayment(reservation);
        fixtures.refund(paymentId, "REFUNDED");
        pg.payment = new Approved(new PgPaymentRef("pg-tx-" + reservation), new BigDecimal("50000"), "KRW", Instant.now());
        pg.refund = new Refunded("ref-1", new BigDecimal("1000"), "KRW", Instant.now());
        assertTrue(String.join("\n", inspect.report(args("payment-id=" + paymentId, "with-pg")))
                .contains("환불: PG=환불 완료"));
        assertTrue(String.join("\n", inspect.report(args("payment-id=" + paymentId, "with-pg")))
                .contains("불일치: 금액·통화가 다르다"));

        pg.refund = new NotFound();
        assertTrue(String.join("\n", inspect.report(args("payment-id=" + paymentId, "with-pg")))
                .contains("불일치: DB는 환불 완료인데 PG에 환불이 없다"));
    }

    @Test
    void with_pg는_PG를_조회하지_못하면_그_사실만_보여_준다() {
        var paymentId = fixtures.approvedPayment(OpsFixtures.nextReservation());
        fixtures.refund(paymentId, "REQUESTED");

        pg.payment = new Unknown(UnknownCause.TIMEOUT);
        pg.refund = new Unknown(UnknownCause.PG_ERROR);
        var unknown = String.join("\n", inspect.report(args("payment-id=" + paymentId, "with-pg")));
        assertTrue(unknown.contains("결제: PG를 조회하지 못했다(결과 불확실: TIMEOUT)"), unknown);
        assertTrue(unknown.contains("환불: PG를 조회하지 못했다(결과 불확실: PG_ERROR)"), unknown);

        pg.failure = new IllegalStateException("연결 거부");
        var failed = String.join("\n", inspect.report(args("payment-id=" + paymentId, "with-pg")));
        assertTrue(failed.contains("결제: PG를 조회하지 못했다(IllegalStateException)"), failed);
        assertFalse(failed.contains("연결 거부"), "예외 메시지에 비밀값이 섞일 수 있어 종류만 보인다");
    }

    @Test
    void PG_클라이언트가_없어도_조사_결과는_나온다() {
        var paymentId = fixtures.approvedPayment(OpsFixtures.nextReservation());
        var withoutPg = new InspectQuery(inspection, provider(null));

        var output = String.join("\n", withoutPg.report(args("payment-id=" + paymentId, "with-pg")));

        assertTrue(output.contains("PG 클라이언트가 설정되어 있지 않아 조회하지 못했다"), output);
    }

    private static ObjectProvider<PgClient> provider(PgClient client) {
        return new ObjectProvider<>() {
            @Override
            public PgClient getObject() { return client; }

            @Override
            public PgClient getObject(Object... args) { return client; }

            @Override
            public PgClient getIfAvailable() { return client; }

            @Override
            public PgClient getIfUnique() { return client; }
        };
    }

    // "이름=값"은 값 있는 옵션, "이름"만 있으면 스위치다
    private static OpsArguments args(String... options) {
        var parsed = new LinkedHashMap<String, List<String>>();
        for (var option : options) {
            var separator = option.indexOf('=');
            if (separator < 0) {
                parsed.put(option, List.of());
            } else {
                parsed.put(option.substring(0, separator), List.of(option.substring(separator + 1)));
            }
        }
        return new OpsArguments(List.of("ops", "inspect"), parsed);
    }
}
