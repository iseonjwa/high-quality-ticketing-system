package com.iseonjwa.payment.reconciliation;

import com.iseonjwa.payment.reconciliation.application.ReconciliationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 예매 서비스를 부르는 동안 DB 트랜잭션이 걸려 있지 않음을 확인한다(events-and-reliability.md 7절: 배경 작업은 원격 호출 동안 DB
 * 연결을 잡지 않는다). 테스트 자체가 트랜잭션 안이면 항상 걸려 있으므로 이 클래스는 트랜잭션 없이 돌고 행은 직접 지운다.
 */
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "payment.reconciliation.reservation.enabled=true", "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.minimum-idle=1"})
@ActiveProfiles("test")
@Import(FakeReservationConfiguration.class)
class ReservationLookupTransactionTest {
    private static final Instant CREATED = Instant.parse("2026-11-03T00:00:00Z");

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ReconciliationService service;

    private long paymentId;

    @BeforeEach
    void setUp() {
        FakeReservationConfiguration.SERVER.reset();
        FakeReservationConfiguration.TRANSACTION_ACTIVE_AT_CALL.clear();
        var fixtures = new ReconciliationFixtures(jdbc);
        paymentId = fixtures.payment(fixtures.newReservation(), "APPROVED", CREATED);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM reconciliation_mismatches");
        jdbc.update("DELETE FROM payments WHERE id = ?", paymentId);
    }

    @Test
    void 예매_조회는_트랜잭션_밖에서_부르고_판단과_기록은_그_뒤에_한다() {
        service.runOnce(CREATED.plus(Duration.ofMinutes(11)));

        var calls = FakeReservationConfiguration.TRANSACTION_ACTIVE_AT_CALL;
        assertTrue(calls.size() >= 2, "묶음 조회와 변경 목록을 불렀다: " + calls);
        assertFalse(calls.contains(true), "원격 호출 중에는 트랜잭션이 없다: " + calls);
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM reconciliation_mismatches WHERE rule = 'RC-2' AND payment_id = ?", Integer.class, paymentId),
                "판단·기록은 준비가 끝난 뒤 정상적으로 이어진다");
    }
}
