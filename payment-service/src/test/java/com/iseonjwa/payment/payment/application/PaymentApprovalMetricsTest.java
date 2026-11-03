package com.iseonjwa.payment.payment.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.payment.observability.ApproveOutcome;
import com.iseonjwa.payment.observability.TransitionOperation;
import com.iseonjwa.payment.payment.domain.DeclineReason;
import com.iseonjwa.payment.payment.domain.IdempotencyRecordRepository;
import com.iseonjwa.payment.payment.domain.PaymentRepository;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgPaymentRef;
import com.iseonjwa.payment.pg.application.UnknownCause;
import com.iseonjwa.payment.pg.infrastructure.Resilience4jPgApprovalGate;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static com.iseonjwa.payment.support.PaymentTokenFixtures.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

// 승인 API의 결과별 Timer 건수를 확인한다(ISE-156). 호출 하나는 어떤 끝(반환·예외)이든 정확히 한 번 기록된다.
// Note: 스프링 컨텍스트 설정(웹 환경, 대체·스파이 빈)을 PaymentApprovalApiTest와 똑같이 맞춘다. 설정이 다르면 컨텍스트가 따로 생겨
// 풀마다 DB 연결을 잡아 테스트 PostgreSQL의 연결 한도(too many clients)를 넘긴다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class PaymentApprovalMetricsTest {
    private static final long USER = 42L;
    private static final long AMOUNT = 50000L;
    private static final AtomicLong RESERVATION_IDS = new AtomicLong(8_900_000);
    private static final long FIRST_RESERVATION = 8_900_000;
    private static final long LAST_RESERVATION = 8_999_999;

    @Autowired
    PaymentApprovalService service;
    @Autowired
    MeterRegistry meters;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Resilience4jPgApprovalGate approvalGate;
    @MockitoBean
    PgClient pg;
    @MockitoSpyBean
    PaymentTx paymentTx;
    @MockitoSpyBean
    IdempotencyRecordRepository idempotency;
    @MockitoSpyBean
    PaymentRepository payments;

    @BeforeEach
    void stubPg() {
        reset(pg, paymentTx, idempotency, payments);
        approvalGate.circuitBreaker().reset();
        when(pg.approve(any())).thenAnswer(invocation -> approved());
    }

    @AfterEach
    void cleanUp() {
        approvalGate.circuitBreaker().reset();
        jdbc.update("DELETE FROM idempotency_records WHERE resource_id IN (SELECT id FROM payments WHERE reservation_id BETWEEN ? AND ?)",
                FIRST_RESERVATION, LAST_RESERVATION);
        jdbc.update("DELETE FROM outbox_events WHERE message_key ~ '^89[0-9]{5}$'");
        jdbc.update("DELETE FROM payment_status_histories WHERE payment_id IN (SELECT id FROM payments WHERE reservation_id BETWEEN ? AND ?)",
                FIRST_RESERVATION, LAST_RESERVATION);
        jdbc.update("DELETE FROM payments WHERE reservation_id BETWEEN ? AND ?", FIRST_RESERVATION, LAST_RESERVATION);
    }

    @Test
    void PG가_승인하면_approved로_한_번_센다() {
        var before = counts();

        service.approve(command(newReservation(), UUID.randomUUID()));

        assertDelta(before, ApproveOutcome.APPROVED);
    }

    @Test
    void PG가_거절하면_declined로_한_번_센다() {
        when(pg.approve(any())).thenReturn(new PgClient.Declined(DeclineReason.CARD_DECLINED));
        var before = counts();

        service.approve(command(newReservation(), UUID.randomUUID()));

        assertDelta(before, ApproveOutcome.DECLINED);
    }

    @Test
    void PG_결과를_모르면_unknown으로_한_번_센다() {
        when(pg.approve(any())).thenReturn(new PgClient.Unknown(UnknownCause.TIMEOUT));
        var before = counts();

        service.approve(command(newReservation(), UUID.randomUUID()));

        assertDelta(before, ApproveOutcome.UNKNOWN);
    }

    @Test
    void PG가_예외를_던져도_unknown으로_한_번_센다() {
        when(pg.approve(any())).thenThrow(new IllegalStateException("어댑터 장애"));
        var before = counts();

        service.approve(command(newReservation(), UUID.randomUUID()));

        assertDelta(before, ApproveOutcome.UNKNOWN);
    }

    @Test
    void 같은_키_재요청과_새_키_연결은_linked로_센다() {
        var reservation = newReservation();
        var first = command(reservation, UUID.randomUUID());
        service.approve(first);
        var before = counts();

        service.approve(first);
        assertDelta(before, ApproveOutcome.LINKED);

        var beforeAlias = counts();
        service.approve(new ApprovePaymentCommand(USER, UUID.randomUUID(), first.paymentToken(), AMOUNT));
        assertDelta(beforeAlias, ApproveOutcome.LINKED);
    }

    @Test
    void 토큰_금액과_요청_금액이_다르면_rejected로_센다() {
        var before = counts();
        var mismatched = new ApprovePaymentCommand(USER, UUID.randomUUID(),
                token(USER, newReservation(), AMOUNT).build(), AMOUNT + 1);

        assertThrows(BusinessException.class, () -> service.approve(mismatched));

        assertDelta(before, ApproveOutcome.REJECTED);
    }

    @Test
    void 종료된_결제를_다시_승인하면_예외가_아니라_거절_응답이어도_rejected로_센다() {
        when(pg.approve(any())).thenReturn(new PgClient.Declined(DeclineReason.OTHER));
        var reservation = newReservation();
        service.approve(command(reservation, UUID.randomUUID()));
        var before = counts();

        var outcome = service.approve(command(reservation, UUID.randomUUID()));

        assertEquals(409, outcome.httpStatus());
        assertDelta(before, ApproveOutcome.REJECTED);
    }

    @Test
    void 승인_허가를_못_얻으면_unavailable로_센다() {
        approvalGate.circuitBreaker().transitionToOpenState();
        var before = counts();

        var thrown = assertThrows(BusinessException.class,
                () -> service.approve(command(newReservation(), UUID.randomUUID())));

        assertEquals(503, thrown.getErrorCode().status());
        assertDelta(before, ApproveOutcome.UNAVAILABLE);
    }

    @Test
    void PG_호출_전_구간의_DB_오류는_error로_센다() {
        doThrow(new DataAccessResourceFailureException("DB 연결 실패")).when(paymentTx).createRequested(any(), any(),
                any());
        var before = counts();

        assertThrows(BusinessException.class, () -> service.approve(command(newReservation(), UUID.randomUUID())));

        assertDelta(before, ApproveOutcome.ERROR);
    }

    @Test
    void 처리_중_예상하지_못한_예외도_error로_센다() {
        doThrow(new IllegalStateException("예상하지 못한 오류")).when(paymentTx).createRequested(any(), any(), any());
        var before = counts();

        assertThrows(IllegalStateException.class, () -> service.approve(command(newReservation(), UUID.randomUUID())));

        assertDelta(before, ApproveOutcome.ERROR);
    }

    @Test
    void TX2의_조건부_UPDATE가_0행이면_tx2_전이_충돌로_센다() {
        var reservation = newReservation();
        service.approve(command(reservation, UUID.randomUUID()));
        var approved = payments.findByReservationId(reservation).orElseThrow();
        var before = conflicts(TransitionOperation.TX2);

        // 이미 APPROVED라 REQUESTED에서 거절로 바꾸는 UPDATE는 0행이다
        paymentTx.settleDeclined(approved, command(reservation, UUID.randomUUID()), DeclineReason.OTHER);

        assertEquals(before + 1, conflicts(TransitionOperation.TX2));
    }

    private ApprovePaymentCommand command(long reservation, UUID key) {
        return new ApprovePaymentCommand(USER, key, token(USER, reservation, AMOUNT).build(), AMOUNT);
    }

    private long newReservation() {
        return RESERVATION_IDS.incrementAndGet();
    }

    private static PgClient.Approved approved() {
        return new PgClient.Approved(new PgPaymentRef("pg-" + UUID.randomUUID()), BigDecimal.valueOf(AMOUNT), "KRW",
                Instant.now());
    }

    private double[] counts() {
        return Arrays.stream(ApproveOutcome.values()).mapToDouble(this::count).toArray();
    }

    private double count(ApproveOutcome outcome) {
        return meters.get("payment.approve").tag("outcome", outcome.name().toLowerCase(Locale.ROOT)).timer().count();
    }

    // 기대한 결과만 1 늘고 나머지는 그대로여야 한다. 호출 하나가 두 번 기록되거나 빠지면 여기서 드러난다
    private void assertDelta(double[] before, ApproveOutcome expected) {
        for (var outcome : ApproveOutcome.values()) {
            var delta = count(outcome) - before[outcome.ordinal()];
            assertEquals(outcome == expected ? 1 : 0, delta, "outcome=" + outcome);
        }
    }

    private double conflicts(TransitionOperation operation) {
        return meters.get("payment.transition.conflict").tag("operation", operation.name().toLowerCase(Locale.ROOT))
                .counter().count();
    }
}
