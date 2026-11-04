package com.iseonjwa.payment.scenario;

import com.iseonjwa.payment.reconciliation.FakeReservationConfiguration;
import com.iseonjwa.payment.reconciliation.FakeReservationServer;
import com.iseonjwa.payment.reconciliation.domain.ReservationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N23: {@code RefundRequested}가 발행되지 않음(Reservation 소비 실패 모의). 실제 승인 흐름으로 만든 {@code APPROVED} 결제의
 * 예매가 {@code EXPIRED}가 됐는데 환불 행이 없으면 허용 지연 뒤 RC-2가 {@code OPEN}으로 기록되고 Gauge가 오른다. 자동 수정은
 * 없다. 나중에 환불 행이 생기면(운영자가 환불을 요청) 다음 회차에 {@code RESOLVED}로 닫힌다. 예매 서비스는 가짜 서버로 대신한다.
 *
 * <p>Note: 대사는 DB 전체를 읽으므로 단언은 이 시나리오의 예매 ID 범위로 한정한다. Gauge는 DB 전체의 {@code OPEN} 건수라
 * DB의 건수와 같고 1 이상인지로 확인한다.
 */
@TestPropertySource(properties = "payment.reconciliation.reservation.enabled=true")
@Import(FakeReservationConfiguration.class)
class ReconciliationReservationScenarioTest extends ScenarioTestBase {
    private static final FakeReservationServer RESERVATIONS = FakeReservationConfiguration.SERVER;

    private static Instant afterLag() {
        return Instant.now().plus(Duration.ofMinutes(11));
    }

    @BeforeEach
    void resetReservations() {
        RESERVATIONS.reset();
    }

    @AfterEach
    void clearMismatches() {
        jdbc.update("DELETE FROM reconciliation_mismatches");
        RESERVATIONS.reset();
    }

    @Test
    void N23_환불_요청이_오지_않으면_RC2가_OPEN이고_환불_행이_생기면_닫힌다() throws Exception {
        var reservation = newReservation();
        var approval = approve(reservation, UUID.randomUUID());
        assertEquals("APPROVED", approval.paymentStatus());
        var paymentId = approval.paymentId();
        // 예매는 만료됐는데 RefundRequested가 오지 않아 환불 행이 없다
        RESERVATIONS.put(FakeReservationServer.reservation(reservation, USER, ReservationStatus.EXPIRED, AMOUNT, null,
                Instant.now().minus(Duration.ofHours(1))));

        reconciliation.runOnce(Instant.now().plus(Duration.ofMinutes(5)));
        assertEquals(List.of(), openInRange(), "허용 지연 안에서는 기록하지 않는다");

        reconciliation.runOnce(afterLag());
        gauges.refresh();
        assertEquals(List.of("RC-2 " + paymentId), openInRange(), "자동 수정 없이 기록만 한다");
        assertEquals(0, refundCount(reservation));
        assertEquals("APPROVED", paymentStatus(reservation));
        assertTrue(openCount("RC-2") >= 1);
        assertEquals(openCount("RC-2"), gaugeValue("RC-2"), "Gauge는 DB의 RC-2 OPEN 건수와 같다");

        // 운영자가 환불을 요청해 환불 행이 생긴다
        commandListener.onMessage(refundRequested(newEventId(), reservation, paymentId));
        assertEquals(1, refundCount(reservation));
        reconciliation.runOnce(afterLag());
        gauges.refresh();

        assertEquals(List.of(), openInRange());
        assertEquals("RESOLVED", jdbc.queryForObject(
                "SELECT status FROM reconciliation_mismatches WHERE payment_id = ? AND rule = 'RC-2'", String.class, paymentId));
        assertEquals(openCount("RC-2"), gaugeValue("RC-2"));
    }

    private List<String> openInRange() {
        return jdbc.queryForList("""
                SELECT rule || ' ' || payment_id FROM reconciliation_mismatches
                WHERE status = 'OPEN' AND reservation_id BETWEEN ? AND ? AND rule < 'RC-7' ORDER BY id""", String.class,
                FIRST_RESERVATION, LAST_RESERVATION);
    }

    private double openCount(String rule) {
        return jdbc.queryForObject("SELECT count(*) FROM reconciliation_mismatches WHERE rule = ? AND status = 'OPEN'",
                Double.class, rule);
    }

    private double gaugeValue(String rule) {
        return meters.find("reconciliation.mismatch.open").tag("rule", rule).gauge().value();
    }
}
