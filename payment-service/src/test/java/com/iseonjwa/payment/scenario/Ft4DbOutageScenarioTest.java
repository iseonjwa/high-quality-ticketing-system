package com.iseonjwa.payment.scenario;

import com.iseonjwa.payment.pg.infrastructure.Resilience4jPgApprovalGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.TransactionException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.iseonjwa.payment.support.PaymentTokenFixtures.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/**
 * FT-4 DB 일시 장애(failure-scenarios.md 4절). 실제 DB를 멈추지 않고 테스트 전용 {@code DataSource} 감싸개의 스위치를 켜서 새
 * 연결을 얻는 순간을 실패시킨다. 타이밍에 기대지 않도록 장애가 시작되는 지점은 PG 호출 직후나 허가 획득 직후 같은 호출 경계의
 * 훅으로 정한다. 시나리오마다 끝에서 V1~V3를 모두 실행한다.
 *
 * <p>Note: 스위치는 {@code getConnection} 시점만 막는다. Hikari 풀에서 이미 빌려 간 연결은 영향을 받지 않는다. 그래서 트랜잭션이
 * 시작되기 전(연결을 얻는 순간)의 장애만 만든다. 실제 {@code postgres-payment} 일시 정지에서 진행 중이던 쿼리가 끊기는 경우는
 * compose에서 메인이 수동으로 확인한다.
 *
 * <p>Note: 스위치가 켜져 있으면 테스트의 {@code jdbc}도 같은 {@code DataSource}라 실패한다. 상태 조회는 스위치를 끈 뒤에 한다.
 */
@Import(Ft4DbOutageScenarioTest.DbSwitchConfiguration.class)
class Ft4DbOutageScenarioTest extends ScenarioTestBase {
    private static final DbSwitch DB = new DbSwitch();

    // 허가를 얻은 직후 장애를 시작해 TX1을 실패시키는 훅이다. 허가는 실제 게이트가 내준 것이다
    @MockitoSpyBean
    Resilience4jPgApprovalGate approvalGateSpy;

    @BeforeEach
    void dbUp() {
        DB.up();
    }

    // 하위 클래스의 @AfterEach가 먼저 실행된다. 기반의 정리(jdbc)가 돌기 전에 DB를 되살린다
    @AfterEach
    void dbBackUp() {
        DB.up();
    }

    @Test
    void a_TX1에서_DB가_실패하면_503이고_결제_행도_멱등_기록도_없고_허가를_돌려주며_같은_키로_다시_하면_성공한다() throws Exception {
        var reservation = newReservation();
        var key = UUID.randomUUID();
        var paymentToken = token(USER, reservation, AMOUNT).build();
        // 신규 승인의 허가를 얻은 직후 DB가 끊긴다. 이어지는 TX1이 연결을 얻지 못한다(N5)
        doAnswer(invocation -> {
            var permit = invocation.callRealMethod();
            DB.down();
            return permit;
        }).when(approvalGateSpy).acquire();

        var failed = approve(reservation, key, paymentToken);
        DB.up();

        assertEquals(503, failed.status(), String.valueOf(failed.body()));
        assertEquals("SERVICE_UNAVAILABLE", failed.body().get("code").asString());
        assertEquals(0, paymentCount(reservation), "TX1이 롤백돼 결제 행이 없다");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE idem_key = ?", Integer.class,
                key), "멱등 기록도 없다");
        assertTrue(PG.ledger().approvedOrders().isEmpty());
        assertEquals(0, PG.ledger().summary().approvedCount(), "PG 호출 전이다");
        var bulkhead = approvalGateSpy.bulkhead();
        assertEquals(bulkhead.getBulkheadConfig().getMaxConcurrentCalls(), bulkhead.getMetrics().getAvailableConcurrentCalls(),
                "허가를 돌려줬다");
        assertEquals(0, approvalGateSpy.circuitBreaker().getMetrics().getNumberOfBufferedCalls(),
                "호출하지 않은 허가는 CB에 결과로 세지 않는다");
        invariants().assertHolds();

        // 장애가 끝난 뒤 같은 키·같은 요청은 새 요청처럼 성공한다
        reset(approvalGateSpy);
        var retried = approve(reservation, key, paymentToken);

        assertEquals(200, retried.status(), String.valueOf(retried.body()));
        assertEquals("APPROVED", retried.paymentStatus());
        assertEquals(1, paymentCount(reservation));
        assertEquals(1, PG.ledger().payment(pgOrderId(reservation).toString()).orElseThrow().approveCalls());
        recoverUntilIdle();
        assertEquals(List.of("PaymentApproved"), outboxEventTypes(reservation));
        invariants().assertHolds();
    }

    @Test
    void b_PG_승인_뒤_TX2에서_DB가_실패하면_결제는_REQUESTED로_남고_DB가_돌아온_뒤_재확인이_APPROVED로_확정하고_승인은_한_번이다() throws Exception {
        var reservation = newReservation();
        // PG가 승인을 마친 직후 DB가 끊긴다. TX2가 연결을 얻지 못한다
        doAnswer(invocation -> {
            var result = invocation.callRealMethod();
            DB.down();
            return result;
        }).when(pg).approve(any());

        var response = approve(reservation, UUID.randomUUID());
        DB.up();

        assertEquals(202, response.status(), "TX2가 실패해도 PG 결과는 정해졌으므로 202로 응답한다: " + response.body());
        assertEquals("REQUESTED", response.paymentStatus(), "TX2가 롤백돼 REQUESTED로 남는다");
        assertEquals("REQUESTED", paymentStatus(reservation));
        assertEquals(null, pgTxId(reservation));
        var order = PG.ledger().payment(pgOrderId(reservation).toString()).orElseThrow();
        assertEquals("APPROVED", order.status(), "PG에는 승인이 남았다");
        assertEquals(1, order.approveCalls());
        assertEquals(List.of(), outboxEventTypes(reservation), "확정 전에는 결과 이벤트가 없다");

        recoverUntilIdle();

        assertEquals("APPROVED", paymentStatus(reservation));
        assertEquals(order.pgTxId(), pgTxId(reservation));
        assertEquals(1, PG.ledger().payment(pgOrderId(reservation).toString()).orElseThrow().approveCalls(),
                "재확인은 조회만 한다");
        assertEquals(List.of("PaymentApproved"), outboxEventTypes(reservation));
        invariants().assertHolds();
    }

    @Test
    void c_payment_command_소비_중_DB_장애는_재시도_대상_예외이고_DLQ_처리기도_DB가_돌아올_때까지_예외이며_돌아오면_환불을_영속화한다() throws Exception {
        var reservation = newReservation();
        var approval = approve(reservation, UUID.randomUUID());
        assertEquals("APPROVED", approval.paymentStatus());
        var eventId = newEventId();
        var message = refundRequested(eventId, reservation, approval.paymentId());
        var offset = nextDlqOffset();

        // 소비 중 DB 장애: 내용 문제(영구 오류)가 아니라 DB 오류라서 재시도 대상이다. 컨테이너는 3회 재시도 뒤 DLQ로 보낸다
        DB.down();
        var consumeFailure = assertThrows(RuntimeException.class, () -> commandListener.onMessage(message));
        // DLQ 처리기도 같은 장애를 만나면 예외를 던진다. 컨테이너가 오프셋을 올리지 않고 무제한으로 다시 처리한다
        var dlqFailure1 = assertThrows(RuntimeException.class, () -> deliverToDlq(message, offset));
        var dlqFailure2 = assertThrows(RuntimeException.class, () -> deliverToDlq(message, offset));
        DB.up();

        for (var failure : List.of(consumeFailure, dlqFailure1, dlqFailure2)) {
            assertTrue(failure instanceof TransactionException || failure instanceof DataAccessException,
                    "내용 문제가 아닌 DB 오류여야 한다: " + failure);
        }
        assertEquals(0, refundCount(reservation), "장애 중에는 아무것도 남지 않는다");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM dead_letters WHERE event_key = ?", Integer.class,
                eventId.toString()));

        // DB가 돌아오면 같은 DLQ 메시지를 다시 처리해 환불을 영속화한다(N7, N8)
        deliverToDlq(message, offset);

        assertEquals(1, refundCount(reservation));
        assertEquals("REQUESTED", refundStatus(reservation));
        assertEquals("AUTO_PERSISTED", jdbc.queryForObject("SELECT resolution FROM dead_letters WHERE event_key = ?",
                String.class, eventId.toString()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM dead_letters WHERE event_key = ?", Integer.class,
                eventId.toString()));
        invariants().assertHolds();

        recoverUntilIdle();

        assertEquals("REFUNDED", refundStatus(reservation));
        assertEquals("REFUNDED", paymentStatus(reservation));
        assertEquals(1, PG.ledger().refunds(pgTxId(reservation)).refundCount());
        assertEquals(List.of("PaymentApproved", "PaymentRefunded"), outboxEventTypes(reservation));
        invariants().assertHolds();
    }

    @Test
    void d_환불_결과_기록_중_DB가_실패하면_임대가_끝난_뒤_findRefund로_확정하고_PG_환불은_한_번이다() throws Exception {
        var reservation = approvedPaymentWithRefundRequest();
        var refundId = pgRefundId(reservation);
        var pgTxId = pgTxId(reservation);
        // PG 환불을 마친 직후 DB가 끊긴다. 결과를 기록하는 트랜잭션이 연결을 얻지 못한다
        doAnswer(invocation -> {
            var result = invocation.callRealMethod();
            DB.down();
            return result;
        }).when(pg).refund(any());

        var failure = assertThrows(RuntimeException.class, refundWorker::processNext);
        DB.up();

        assertTrue(failure instanceof TransactionException || failure instanceof DataAccessException, String.valueOf(failure));
        assertEquals("REQUESTED", refundStatus(reservation), "결과를 기록하지 못했다");
        assertEquals("APPROVED", paymentStatus(reservation));
        assertEquals(1, PG.ledger().refunds(pgTxId).refundCount(), "PG에는 환불이 남았다");
        var nextCheckAt = jdbc.queryForObject("SELECT next_check_at FROM refunds WHERE pg_refund_id = ?",
                java.sql.Timestamp.class, refundId);
        assertTrue(nextCheckAt.toInstant().isAfter(Instant.now()), "임대 중이다");
        assertFalse(refundWorker.processNext(), "임대가 끝나기 전에는 다시 집지 않는다");

        // DB가 돌아온 뒤에는 끊는 훅이 없다. 환불을 다시 부르면 호출 수로 드러나게 스텁을 푼다
        reset(pg);
        recoverUntilIdle();

        assertEquals("REFUNDED", refundStatus(reservation));
        assertEquals("REFUNDED", paymentStatus(reservation));
        var refunds = PG.ledger().refunds(pgTxId);
        assertEquals(1, refunds.refundCount());
        assertEquals(refundId.toString(), refunds.refunds().get(0).refundId());
        assertEquals(1, refunds.refunds().get(0).refundCalls(), "임대 만료 뒤 환불을 다시 부르지 않고 조회로 확정했다");
        assertEquals(1, refunds.refunds().get(0).refundLookupCalls());
        invariants().assertHolds();
    }

    /** 켜면 새 연결을 얻는 순간 {@link SQLTransientConnectionException}을 던지는 스위치. */
    static final class DbSwitch {
        private volatile boolean down;

        void down() {
            down = true;
        }

        void up() {
            down = false;
        }

        void check() throws SQLException {
            if (down) throw new SQLTransientConnectionException("테스트: DB 일시 장애");
        }
    }

    static final class SwitchableDataSource extends DelegatingDataSource {
        private final DbSwitch dbSwitch;

        SwitchableDataSource(DataSource target, DbSwitch dbSwitch) {
            super(target);
            this.dbSwitch = dbSwitch;
        }

        @Override
        public Connection getConnection() throws SQLException {
            dbSwitch.check();
            return super.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            dbSwitch.check();
            return super.getConnection(username, password);
        }
    }

    /** 이 시나리오 클래스의 컨텍스트에서만 {@code DataSource}를 감싼다. 운영 코드는 건드리지 않는다. */
    @TestConfiguration(proxyBeanMethods = false)
    static class DbSwitchConfiguration {
        @Bean
        static BeanPostProcessor switchableDataSourcePostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof DataSource dataSource && "dataSource".equals(beanName)
                            ? new SwitchableDataSource(dataSource, DB) : bean;
                }
            };
        }
    }
}
