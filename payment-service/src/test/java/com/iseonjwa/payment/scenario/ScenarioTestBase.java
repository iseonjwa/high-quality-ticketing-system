package com.iseonjwa.payment.scenario;

import com.iseonjwa.common.messaging.outbox.OutboxEventSender;
import com.iseonjwa.common.messaging.outbox.OutboxRelay;
import com.iseonjwa.common.web.GatewayIdentityFilter;
import com.iseonjwa.payment.observability.DbAggregateGauges;
import com.iseonjwa.payment.payment.application.PaymentRecheckService;
import com.iseonjwa.payment.pg.application.ApproveCommand;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.infrastructure.MockPgProcess;
import com.iseonjwa.payment.pg.infrastructure.Resilience4jPgApprovalGate;
import com.iseonjwa.payment.pg.infrastructure.Resilience4jPgRecoveryGate;
import com.iseonjwa.payment.refund.application.RefundWorkerService;
import com.iseonjwa.payment.refund.infrastructure.messaging.PaymentCommandDlqListener;
import com.iseonjwa.payment.refund.infrastructure.messaging.PaymentCommandListener;
import com.iseonjwa.payment.support.AppendOnlyCleanup;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static com.iseonjwa.payment.support.PaymentTokenFixtures.token;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * 장애 시나리오 테스트의 공통 기반(failure-scenarios.md 4절). 실제 Mock PG 프로세스(test 프로필)와 테스트 PostgreSQL을 쓰고,
 * Kafka 리스너와 배경 실행기는 test 프로필처럼 꺼 둔다. 리스너·재확인 서비스·환불 워커·DLQ 처리기·Outbox 릴레이는 시나리오가
 * 직접 부르고, 복구는 {@link #recoverUntilIdle()}이 할 일이 없을 때까지 돌린다.
 *
 * <p>Note: 컨텍스트 설정(속성, Mock PG 주소, 빈 대체)을 이 클래스 한 곳에 모았다. 시나리오 클래스는 설정을 더하지 않아서
 * 한 클래스 안의 시나리오는 같은 컨텍스트를 재사용한다. 다만 Mock PG 주소와 빈 대체 때문에 다른 테스트의 컨텍스트와는 나눠 쓰지
 * 못하고, 컨텍스트마다 커넥션 풀이 생겨 캐시에 남기면 테스트 DB 연결 한도(100)를 채운다(ISE-156). 그래서 풀을 5개로 줄이고
 * 클래스가 끝나면 컨텍스트를 닫는다({@code @DirtiesContext}).
 *
 * <p>Note: 기다림은 시계를 앞당겨 없앤다. 재확인·환불 워커·Outbox 릴레이의 다음 시도 시각과 임대 만료를 이 시나리오의 행에 한해
 * 과거로 당긴다({@link #advanceTime()}). 기존 통합 테스트가 쓰는 방식(다음 시도 시각 갱신)과 같고, 운영 코드의 시계
 * 빈({@code commonAuditingClock})을 바꾸지 않는다. 어디에서도 {@code sleep}으로 기다리지 않는다.
 */
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=5", "spring.datasource.hikari.minimum-idle=1"})
@ActiveProfiles("test")
// Note: Mock PG 주소와 빈 대체가 달라 다른 테스트와 컨텍스트를 나눠 쓰지 못한다. 클래스가 끝나면 컨텍스트를 닫아 연결을 돌려준다
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class ScenarioTestBase {
    // 시나리오 클래스의 @Order 값. 다른 통합 테스트보다 먼저 돌려 테스트 DB 연결을 확보한다(src/test/resources/junit-platform.properties)
    static final int RUN_FIRST = -100;
    static final long USER = 71L;
    static final long AMOUNT = 50000L;
    // 이 범위의 예매 ID는 시나리오 테스트만 쓴다
    static final long FIRST_RESERVATION = 9_400_000;
    static final long LAST_RESERVATION = 9_499_999;
    // 시나리오가 DLQ로 보낸 메시지의 원본 파티션 표식. dead_letters를 이 번호로 가려 지운다
    static final int DLQ_PARTITION = 97;
    private static final AtomicLong RESERVATION_IDS = new AtomicLong(FIRST_RESERVATION);
    private static final int MAX_RECOVERY_ROUNDS = 40;
    private static final int MAX_DRAIN = 100;

    static final MockPgProcess PG = ScenarioMockPg.get();

    @DynamicPropertySource
    static void mockPgUrl(DynamicPropertyRegistry registry) {
        registry.add("payment.pg.mock.base-url", PG::baseUrl);
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    WebApplicationContext webApplicationContext;
    @Autowired
    PaymentRecheckService recheck;
    @Autowired
    RefundWorkerService refundWorker;
    @Autowired
    OutboxRelay relay;
    @Autowired
    DbAggregateGauges gauges;
    @Autowired
    MeterRegistry meters;
    @Autowired
    PaymentCommandListener commandListener;
    @Autowired
    PaymentCommandDlqListener dlqListener;
    @Autowired
    Resilience4jPgApprovalGate approvalGate;
    @Autowired
    Resilience4jPgRecoveryGate recoveryGate;
    // 승인 호출 직전에 그 주문으로 장애를 거는 데 쓴다(armApproveFault). PG 호출은 그대로 실제 서버로 나간다
    @MockitoSpyBean
    PgClient pg;
    // 실제 Kafka 대신 시나리오가 전송 결과를 정한다. 기본은 브로커가 확인한 것으로 본다
    @MockitoBean
    OutboxEventSender sender;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AtomicReference<PendingFault> pendingApproveFault = new AtomicReference<>();
    private final List<UUID> eventIds = new ArrayList<>();
    private final AtomicLong dlqOffsets = new AtomicLong();
    private MockMvc mockMvc;

    @BeforeEach
    void beginScenario() {
        // Note: Mock PG 원장과 장애 설정을 먼저 비운다. 이전 시나리오의 호출 계수가 이번 검증에 섞이지 않게 하려는 것이다
        PG.ledger().reset();
        cleanUpScenarioData();
        parkOtherCandidates();
        approvalGate.circuitBreaker().reset();
        recoveryGate.circuitBreaker().reset();
        pendingApproveFault.set(null);
        eventIds.clear();
        reset(pg, sender);
        doAnswer(invocation -> {
            var fault = pendingApproveFault.getAndSet(null);
            if (fault != null) {
                var command = (ApproveCommand) invocation.getArgument(0);
                // 이 주문의 승인 요청에만 적용되게 orderId로 한정한다
                PG.applyFault("APPROVE", fault.mode(), fault.count(), command.orderId().value().toString(), null);
            }
            return invocation.callRealMethod();
        }).when(pg).approve(any());
        when(sender.send(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(webApplicationContext.getBean(GatewayIdentityFilter.class)).build();
    }

    @AfterEach
    void endScenario() {
        PG.ledger().reset();
        cleanUpScenarioData();
    }

    /** 시나리오마다 겹치지 않는 예매 ID. 범위가 모자라면 다른 테스트의 범위를 침범하기 전에 실패한다. */
    long newReservation() {
        var id = RESERVATION_IDS.incrementAndGet();
        if (id > LAST_RESERVATION) throw new IllegalStateException("시나리오 예매 ID 범위를 넘었다");
        return id;
    }

    UUID newEventId() {
        var id = UUID.randomUUID();
        eventIds.add(id);
        return id;
    }

    /** 다음 승인 호출(PG로 나가는 승인) 한 건을 그 주문 한정 장애로 만든다. 주문 ID는 TX1이 정하므로 호출 직전에 건다. */
    void armApproveFault(String mode) {
        pendingApproveFault.set(new PendingFault(mode, 1));
    }

    /** 이 시나리오의 검증기(V1~V3). 시나리오 끝에서 {@code assertHolds()}를 부른다. */
    ScenarioInvariants invariants() {
        return new ScenarioInvariants(jdbc, PG.ledger(), gauges, meters, FIRST_RESERVATION, LAST_RESERVATION,
                DLQ_PARTITION);
    }

    // ----- 승인 API -----

    /** 결제 승인 API를 MockMvc로 부른다(Gateway가 넣는 사용자 헤더 포함). */
    Approval approve(long reservation, UUID idempotencyKey) throws Exception {
        return approve(reservation, idempotencyKey, token(USER, reservation, AMOUNT).build());
    }

    Approval approve(long reservation, UUID idempotencyKey, String paymentToken) throws Exception {
        var body = "{\"paymentToken\":\"" + paymentToken + "\",\"amount\":" + AMOUNT + ",\"method\":\"CARD\"}";
        var result = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/payments")
                .contentType("application/json").header("Idempotency-Key", idempotencyKey.toString())
                .header("X-User-Id", Long.toString(USER)).header("X-User-Role", "USER").content(body)).andReturn();
        var response = result.getResponse();
        var text = response.getContentAsString(StandardCharsets.UTF_8);
        return new Approval(response.getStatus(), text.isBlank() ? null : mapper.readTree(text),
                response.getHeader("Location"));
    }

    record Approval(int status, JsonNode body, String location) {
        String paymentStatus() {
            return body.get("data").get("status").asString();
        }

        long paymentId() {
            return body.get("data").get("paymentId").asLong();
        }
    }

    // ----- 결제·환불 조회 -----

    String paymentStatus(long reservation) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE reservation_id = ?", String.class, reservation);
    }

    long paymentId(long reservation) {
        return jdbc.queryForObject("SELECT id FROM payments WHERE reservation_id = ?", Long.class, reservation);
    }

    UUID pgOrderId(long reservation) {
        return jdbc.queryForObject("SELECT pg_order_id FROM payments WHERE reservation_id = ?", UUID.class, reservation);
    }

    String pgTxId(long reservation) {
        return jdbc.queryForObject("SELECT pg_tx_id FROM payments WHERE reservation_id = ?", String.class, reservation);
    }

    int paymentCount(long reservation) {
        return jdbc.queryForObject("SELECT count(*) FROM payments WHERE reservation_id = ?", Integer.class, reservation);
    }

    int refundCount(long reservation) {
        return jdbc.queryForObject("SELECT count(*) FROM refunds WHERE payment_id IN "
                + "(SELECT id FROM payments WHERE reservation_id = ?)", Integer.class, reservation);
    }

    String refundStatus(long reservation) {
        return jdbc.queryForObject("SELECT status FROM refunds WHERE payment_id IN "
                + "(SELECT id FROM payments WHERE reservation_id = ?)", String.class, reservation);
    }

    UUID pgRefundId(long reservation) {
        return jdbc.queryForObject("SELECT pg_refund_id FROM refunds WHERE payment_id IN "
                + "(SELECT id FROM payments WHERE reservation_id = ?)", UUID.class, reservation);
    }

    List<String> outboxEventTypes(long reservation) {
        return jdbc.queryForList("SELECT event_type FROM outbox_events WHERE message_key = ? ORDER BY id", String.class,
                String.valueOf(reservation));
    }

    // ----- 메시지 -----

    /** {@code RefundRequested} 봉투(JSON). 같은 {@code eventId}로 여러 번 만들면 같은 메시지다. */
    String refundRequested(UUID eventId, long reservation, long paymentId) {
        return """
                {"eventId":"%s","eventType":"RefundRequested","version":1,"occurredAt":"2026-11-03T01:00:00.000Z",
                 "producer":"reservation-service","traceId":"4bf92f3577b34da6a3ce929d0e0e4736",
                 "payload":{"reservationId":%d,"paymentId":%d,"userId":%d,"amount":%d,"currency":"KRW",
                 "reason":"CANCELED"}}""".formatted(eventId, reservation, paymentId, USER, AMOUNT);
    }

    /** {@code payment.command.dlq}에 도착한 레코드로 DLQ 처리기를 부른다. 같은 {@code offset}이면 같은 원본 메시지의 재전달이다. */
    void deliverToDlq(String value, long offset) {
        dlqListener.onMessage(new ConsumerRecord<>("payment.command.dlq", DLQ_PARTITION, offset, null, value));
    }

    long nextDlqOffset() {
        return dlqOffsets.incrementAndGet();
    }

    // ----- 복구 -----

    /**
     * 재확인·환불 워커·Outbox 릴레이를 할 일이 없을 때까지 번갈아 부른다. 한 바퀴마다 {@link #advanceTime()}으로 이 시나리오의
     * 다음 시도 시각을 당겨 기다리지 않고 진행한다. 상한을 넘으면 복구가 끝나지 않는 것이므로 실패한다.
     */
    void recoverUntilIdle() {
        for (var round = 1; round <= MAX_RECOVERY_ROUNDS; round++) {
            advanceTime();
            var worked = drain(recheck::recheckNext);
            worked |= drain(refundWorker::processNext);
            worked |= drain(() -> relay.relayOnce().claimed() > 0);
            if (!worked) return;
        }
        throw new AssertionError("복구가 " + MAX_RECOVERY_ROUNDS + "바퀴 안에 끝나지 않았다");
    }

    /** 재확인·워커·릴레이를 한 바퀴만 돌린다. 중간 상태를 보고 싶을 때 쓴다. */
    void recoverOneRound() {
        advanceTime();
        drain(recheck::recheckNext);
        drain(refundWorker::processNext);
        drain(() -> relay.relayOnce().claimed() > 0);
    }

    private static boolean drain(BooleanSupplier step) {
        var worked = false;
        for (var i = 0; i < MAX_DRAIN && step.getAsBoolean(); i++) worked = true;
        return worked;
    }

    /**
     * 이 시나리오의 재확인·환불·Outbox 다음 시도 시각과 임대 만료를 과거로 당긴다. 시계를 앞으로 보낸 것과 같은 효과다.
     * 다른 시나리오나 테스트가 남긴 행은 건드리지 않는다.
     */
    void advanceTime() {
        jdbc.update("UPDATE payments SET next_check_at = now() - interval '1 second' "
                + "WHERE reservation_id BETWEEN ? AND ? AND status IN ('REQUESTED', 'UNKNOWN')",
                FIRST_RESERVATION, LAST_RESERVATION);
        jdbc.update("UPDATE refunds SET next_check_at = now() - interval '1 second' WHERE status IN ('REQUESTED', 'UNKNOWN') "
                + "AND payment_id IN (SELECT id FROM payments WHERE reservation_id BETWEEN ? AND ?)",
                FIRST_RESERVATION, LAST_RESERVATION);
        jdbc.update("""
                UPDATE outbox_events SET next_attempt_at = now() - interval '1 second',
                       claimed_until = CASE WHEN claimed_until IS NULL THEN NULL ELSE now() - interval '1 second' END
                WHERE status = 'PENDING' AND message_key IN
                      (SELECT reservation_id::text FROM payments WHERE reservation_id BETWEEN ? AND ?)""",
                FIRST_RESERVATION, LAST_RESERVATION);
    }

    // 재확인·워커는 DB 전체에서 후보를 집는다. 다른 테스트가 남긴 후보가 끼어들지 않게 이 범위 밖의 후보를 먼 미래로 미룬다
    private void parkOtherCandidates() {
        jdbc.update("UPDATE payments SET next_check_at = now() + interval '1 year' WHERE status IN ('REQUESTED', 'UNKNOWN') "
                + "AND reservation_id NOT BETWEEN ? AND ?", FIRST_RESERVATION, LAST_RESERVATION);
        jdbc.update("UPDATE refunds SET next_check_at = now() + interval '1 year' WHERE status IN ('REQUESTED', 'UNKNOWN') "
                + "AND payment_id NOT IN (SELECT id FROM payments WHERE reservation_id BETWEEN ? AND ?)",
                FIRST_RESERVATION, LAST_RESERVATION);
    }

    /** 이 시나리오 범위의 결제와 딸린 환불·이력·Outbox·멱등 기록·DLQ·처리 이력을 지운다. 시작 전과 끝에 부른다. */
    void cleanUpScenarioData() {
        var inRange = "(SELECT id FROM payments WHERE reservation_id BETWEEN ? AND ?)";
        jdbc.update("DELETE FROM dead_letters WHERE source_partition = ?", DLQ_PARTITION);
        for (var eventId : eventIds) {
            jdbc.update("DELETE FROM processed_events WHERE event_id = ?", eventId);
        }
        jdbc.update("DELETE FROM outbox_events WHERE message_key IN "
                + "(SELECT reservation_id::text FROM payments WHERE reservation_id BETWEEN ? AND ?)",
                FIRST_RESERVATION, LAST_RESERVATION);
        jdbc.update("DELETE FROM idempotency_records WHERE resource_id IN " + inRange, FIRST_RESERVATION, LAST_RESERVATION);
        jdbc.update("DELETE FROM refunds WHERE payment_id IN " + inRange, FIRST_RESERVATION, LAST_RESERVATION);
        AppendOnlyCleanup.deleteHistories(jdbc, "payment_id IN " + inRange, FIRST_RESERVATION, LAST_RESERVATION);
        jdbc.update("DELETE FROM payments WHERE reservation_id BETWEEN ? AND ?", FIRST_RESERVATION, LAST_RESERVATION);
    }

    private record PendingFault(String mode, int count) {
    }
}
