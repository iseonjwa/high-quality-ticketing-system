package com.iseonjwa.payment.scenario;

import com.iseonjwa.payment.reconciliation.application.ReconciliationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 결제 DB만으로 판단하는 대사 규칙(RC-7·RC-8)을 실제 승인·복구·환불 흐름 뒤에 돌린다(ISE-167, N30). 정상 흐름의 끝에서는 불일치가
 * 0이어야 하고, 직접 SQL로 상태를 바꾸면 RC-7로 잡혀야 한다.
 *
 * <p>Note: FT-1~FT-6의 검증기({@link ScenarioInvariants}) 안에 붙이지 않았다. 검증기의 단독 테스트
 * ({@code ScenarioInvariantsTest})가 결제 행을 이력 없이 직접 넣어, 붙이면 RC-7 불일치로 그 테스트가 실패한다. 그래서 같은
 * 기반 클래스 위에 실제 흐름을 다시 만들어 확인한다. 대사는 DB 전체를 읽으므로 단언은 이 시나리오의 예매 ID 범위로 한정하고,
 * 끝나면 대사 기록을 지운다.
 */
class ReconciliationScenarioTest extends ScenarioTestBase {
    // 마지막 갱신에서 허용 지연(10분)이 지난 뒤의 기준 시각. sleep 없이 시계를 앞당긴다
    private static Instant afterLag() {
        return Instant.now().plus(Duration.ofMinutes(11));
    }

    @Autowired
    ReconciliationService reconciliation;

    @AfterEach
    void clearMismatches() {
        jdbc.update("DELETE FROM reconciliation_mismatches");
    }

    @Test
    void 승인_환불_응답_유실_복구_뒤_대사_불일치가_0이다() throws Exception {
        var approved = newReservation();
        assertEquals("APPROVED", approve(approved, UUID.randomUUID()).paymentStatus());

        var refunded = approvedPaymentWithRefundRequest();
        recoverUntilIdle();
        assertEquals("REFUNDED", paymentStatus(refunded));

        var recovered = newReservation();
        armApproveFault("DROP_RESPONSE");
        assertEquals("UNKNOWN", approve(recovered, UUID.randomUUID()).paymentStatus());
        recoverUntilIdle();
        assertEquals("APPROVED", paymentStatus(recovered));

        // 재시작으로 PG 호출 전에 끊긴 결제는 복구가 DECLINED(NOT_SENT)로 닫는다
        var declined = newReservation();
        armApproveCrash(CrashPoint.BEFORE_PG_CALL);
        approveAndCrash(declined, UUID.randomUUID());
        recoverUntilIdle();
        assertEquals("DECLINED", paymentStatus(declined));

        reconciliation.runOnce(afterLag());

        assertEquals(List.of(), openInRange());
    }

    @Test
    void N30_직접_SQL로_바꾼_상태를_RC7로_잡고_이력과_맞추면_닫는다() throws Exception {
        var reservation = newReservation();
        assertEquals("APPROVED", approve(reservation, UUID.randomUUID()).paymentStatus());
        var paymentId = paymentId(reservation);
        jdbc.update("UPDATE payments SET status = 'REFUNDED', refunded_at = now() WHERE id = ?", paymentId);

        reconciliation.runOnce(afterLag());
        gauges.refresh();

        assertEquals(List.of("RC-7 " + paymentId), openInRange());
        assertEquals(openCount("RC-7"), gaugeValue("RC-7"), "Gauge는 DB의 RC-7 OPEN 건수와 같고 1 이상이다");

        jdbc.update("UPDATE payments SET status = 'APPROVED' WHERE id = ?", paymentId);
        reconciliation.runOnce(afterLag());
        gauges.refresh();

        assertEquals(List.of(), openInRange());
        assertEquals("RESOLVED", jdbc.queryForObject(
                "SELECT status FROM reconciliation_mismatches WHERE payment_id = ? AND rule = 'RC-7'", String.class,
                paymentId));
        assertEquals(openCount("RC-7"), gaugeValue("RC-7"));
    }

    private List<String> openInRange() {
        return jdbc.queryForList("""
                SELECT rule || ' ' || payment_id FROM reconciliation_mismatches
                WHERE status = 'OPEN' AND reservation_id BETWEEN ? AND ? ORDER BY id""", String.class,
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
