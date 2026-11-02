package com.iseonjwa.payment.payment.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.iseonjwa.payment.payment.domain.Payment;
import com.iseonjwa.payment.payment.domain.PaymentRepository;
import com.iseonjwa.payment.payment.domain.PaymentStatus;
import com.iseonjwa.payment.payment.domain.StatusActor;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgPaymentRef;
import com.iseonjwa.payment.support.RecheckFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.iseonjwa.payment.support.RecheckFixtures.AMOUNT;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * TX2가 0행이 된 승인 경합의 로그 수준(api.md 1절 "알려진 경합"). 요청 스레드가 멈춘 사이 다른 경로가 결제를 바꿨다.
 * 재확인이 확정할 UNKNOWN은 WARN, 이미 같은 승인이 기록됐으면 조용히, 그 밖의 상태는 ERROR다.
 */
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class PaymentTxApprovalRaceTest {
    @Autowired
    PaymentTx tx;
    @Autowired
    PaymentTransitionService transitions;
    @Autowired
    PaymentRepository payments;
    @Autowired
    JdbcTemplate jdbc;

    private RecheckFixtures fixtures;
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(PaymentTx.class);

    @BeforeEach
    void setUp() {
        fixtures = new RecheckFixtures(payments, jdbc);
        fixtures.cleanUp();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        fixtures.cleanUp();
    }

    @Test
    void 재확인이_UNKNOWN으로_바꾼_결제는_WARN으로_남기고_상태를_바꾸지_않는다() {
        var payment = fixtures.due(PaymentStatus.UNKNOWN, 1);

        settle(payment, "pg-tx-late");

        assertEquals(PaymentStatus.UNKNOWN, fixtures.reload(payment).getStatus());
        assertEquals(1, count(Level.WARN, "PG_APPROVED_RECHECK_PENDING"));
        assertEquals(0, count(Level.ERROR, "PG_APPROVED_NOT_RECORDED"));
        assertEquals(0, fixtures.outboxCount(payment), "0행이면 이벤트를 쓰지 않는다");
    }

    @Test
    void 같은_승인이_이미_기록돼_있으면_조용히_넘긴다() {
        var payment = fixtures.due(PaymentStatus.REQUESTED, 0);
        transitions.approve(payment.getId(), PaymentStatus.REQUESTED, "pg-tx-same", AMOUNT, Instant.now(),
                StatusActor.SYSTEM);

        settle(payment, "pg-tx-same");

        assertEquals(0, count(Level.WARN, "PG_APPROVED_RECHECK_PENDING"));
        assertEquals(0, count(Level.ERROR, "PG_APPROVED_NOT_RECORDED"));
        assertEquals(1, fixtures.outboxCount(payment));
    }

    @Test
    void 그_밖의_상태에서_승인이_도착하면_ERROR로_남긴다() {
        var payment = fixtures.due(PaymentStatus.REQUESTED, 0);
        transitions.markUnresolved(payment.getId(), PaymentStatus.REQUESTED, StatusActor.SYSTEM);

        settle(payment, "pg-tx-unexpected");

        assertEquals(1, count(Level.ERROR, "PG_APPROVED_NOT_RECORDED"));
        assertEquals(0, count(Level.WARN, "PG_APPROVED_RECHECK_PENDING"));
    }

    private void settle(Payment payment, String pgTxId) {
        var command = new ApprovePaymentCommand(1L, UUID.randomUUID(), "unused-token", 50000L);
        var approved = new PgClient.Approved(new PgPaymentRef(pgTxId), AMOUNT, "KRW", Instant.now());
        tx.settleApproved(payment, command, approved);
    }

    private long count(Level level, String event) {
        List<ILoggingEvent> events = logs.list;
        return events.stream().filter(e -> e.getLevel() == level && e.getFormattedMessage().contains(event)).count();
    }
}
