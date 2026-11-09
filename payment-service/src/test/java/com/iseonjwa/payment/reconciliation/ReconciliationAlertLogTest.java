package com.iseonjwa.payment.reconciliation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.iseonjwa.payment.observability.PaymentAlertLog;
import com.iseonjwa.payment.reconciliation.application.ReconciliationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 대사가 새 OPEN 불일치를 건별 알림 줄로 남기는 규칙을 확인한다(ISE-183). 줄은 커밋 뒤에 찍히므로 테스트가 롤백하는
 * {@code ReconciliationServiceTest}와 달리 트랜잭션 없이 커밋되는 행으로 돌리고, 끝나면 비운다.
 */
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class ReconciliationAlertLogTest {
    private static final Instant CREATED = Instant.parse("2026-11-03T00:00:00Z");
    private static final Instant AFTER_LAG = CREATED.plus(Duration.ofMinutes(11));
    private static final String MISMATCH_OPEN = "event=RECONCILIATION_MISMATCH_OPEN ";
    private static final String SUMMARY = "event=RECONCILIATION_MISMATCH_OPEN_SUMMARY ";

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ReconciliationService service;

    private ReconciliationFixtures fixtures;
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(PaymentAlertLog.class);

    @BeforeEach
    void setUp() {
        fixtures = new ReconciliationFixtures(jdbc);
        fixtures.wipe();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        fixtures.wipe();
    }

    @Test
    void 같은_불일치가_이어지는_회차는_한_번만_알리고_해결_뒤_다시_생기면_한_번_더_알린다() {
        var payment = tamperedPayment();

        service.runOnce(AFTER_LAG);
        service.runOnce(AFTER_LAG.plus(Duration.ofMinutes(5)));

        assertEquals(1, lines(MISMATCH_OPEN), "이어지는 회차에서는 다시 알리지 않는다");
        var line = logs.list.get(0);
        assertEquals(Level.ERROR, line.getLevel());
        assertTrue(line.getFormattedMessage().matches("event=RECONCILIATION_MISMATCH_OPEN paymentId=" + payment
                + " userId=1 reservationId=\\d+ paidAt=2026-11-03T09:00:00\\+09:00 rule=RC-7"), line.getFormattedMessage());

        jdbc.update("UPDATE payments SET status = 'APPROVED' WHERE id = ?", payment);
        service.runOnce(AFTER_LAG.plus(Duration.ofMinutes(10)));
        assertEquals(1, lines(MISMATCH_OPEN), "해결되는 회차에는 알리지 않는다");

        jdbc.update("UPDATE payments SET status = 'REFUNDED' WHERE id = ?", payment);
        service.runOnce(AFTER_LAG.plus(Duration.ofMinutes(15)));
        assertEquals(2, lines(MISMATCH_OPEN), "해결된 뒤 다시 생기면 새 OPEN이라 한 번 더 알린다");
    }

    @Test
    void 새_OPEN이_열_건을_넘으면_열_건까지만_남기고_요약_한_줄을_더한다() {
        for (int i = 0; i < 13; i++) tamperedPayment();

        service.runOnce(AFTER_LAG);

        assertEquals(10, lines(MISMATCH_OPEN));
        assertEquals(1, lines(SUMMARY));
        var summary = logs.list.stream().filter(e -> e.getFormattedMessage().startsWith(SUMMARY)).findFirst()
                .orElseThrow().getFormattedMessage();
        assertEquals("event=RECONCILIATION_MISMATCH_OPEN_SUMMARY rule=RC-7 newOpen=13 logged=10", summary);
    }

    @Test
    void 열_건_이하이면_요약_줄이_없다() {
        for (int i = 0; i < 10; i++) tamperedPayment();

        service.runOnce(AFTER_LAG);

        assertEquals(10, lines(MISMATCH_OPEN));
        assertEquals(0, lines(SUMMARY));
    }

    private long lines(String prefix) {
        return logs.list.stream().filter(e -> e.getFormattedMessage().startsWith(prefix)).count();
    }

    /** 이력은 APPROVED인데 결제 상태만 직접 REFUNDED로 바꾼 결제(RC-7). */
    private long tamperedPayment() {
        var reservation = fixtures.newReservation();
        var payment = fixtures.payment(reservation, "APPROVED", CREATED);
        fixtures.paymentHistory(payment, "REQUESTED", "APPROVED", CREATED);
        fixtures.outbox(reservation, payment, "PaymentApproved", CREATED);
        jdbc.update("UPDATE payments SET status = 'REFUNDED' WHERE id = ?", payment);
        return payment;
    }
}
