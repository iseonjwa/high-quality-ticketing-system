package com.iseonjwa.payment.refund.application;

import com.iseonjwa.payment.refund.domain.RefundReason;
import com.iseonjwa.payment.refund.domain.RefundSource;
import com.iseonjwa.payment.support.RefundFixtures;
import com.iseonjwa.payment.support.RefundFixtures.CreatedPayment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

// 검증과 생성이 예외 없이 결과 값을 돌려주는지 확인한다. 소비자 경로(예외 변환)는 RefundRequestServiceTest가 맡는다.
// Note: 같은 설정의 RefundRequestServiceTest와 스프링 컨텍스트를 공유한다.
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class RefundRequestResultTest {
    @Autowired
    RefundRequestService service;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate transaction;

    private RefundFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new RefundFixtures(jdbc);
        fixtures.cleanUp(List.of());
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanUp(List.of());
    }

    @Test
    void 결제와_맞으면_Valid와_사유를_돌려주고_아무것도_쓰지_않는다() {
        var payment = fixtures.payment("APPROVED");

        var result = service.verify(payload(payment, p -> p));

        assertEquals(new RefundRequestVerification.Valid(RefundReason.CANCELED), result);
        assertEquals(0, fixtures.refundCount(payment.paymentId()));
    }

    @Test
    void 결제가_없으면_예외_없이_Invalid다() {
        var missing = new RefundRequestedPayload(8_899_999L, 999_999_999L, RefundFixtures.USER_ID,
                BigDecimal.valueOf(RefundFixtures.AMOUNT), "KRW", "CANCELED");

        assertInstanceOf(RefundRequestVerification.Invalid.class, service.verify(missing));
    }

    @Test
    void 값이_다르면_예외_없이_Invalid다() {
        var payment = fixtures.payment("APPROVED");

        var amount = service.verify(payload(payment, p -> new RefundRequestedPayload(p.reservationId(), p.paymentId(),
                p.userId(), p.amount().add(BigDecimal.ONE), p.currency(), p.reason())));
        var reason = service.verify(payload(payment, p -> new RefundRequestedPayload(p.reservationId(), p.paymentId(),
                p.userId(), p.amount(), p.currency(), "PG_AMOUNT_MISMATCH")));

        assertInstanceOf(RefundRequestVerification.Invalid.class, amount);
        assertInstanceOf(RefundRequestVerification.Invalid.class, reason);
    }

    @Test
    void 승인된_결제면_Created이고_환불과_이력이_남는다() {
        var payment = fixtures.payment("APPROVED");
        var eventId = UUID.randomUUID();

        var result = transaction.execute(status ->
                service.create(payload(payment, p -> p), RefundReason.CANCELED, RefundSource.DLQ, eventId));

        var created = assertInstanceOf(RefundCreation.Created.class, result);
        var refund = jdbc.queryForMap("SELECT * FROM refunds WHERE payment_id = ?", payment.paymentId());
        assertEquals(created.refundId(), refund.get("id"));
        assertEquals("DLQ", refund.get("source"));
        assertEquals(eventId, refund.get("source_event_id"));
        assertEquals(1, fixtures.refundHistoryCount(payment.paymentId()));
    }

    @Test
    void 환불이_이미_있으면_Duplicate이고_결제가_REFUNDED여도_같다() {
        var payment = fixtures.payment("APPROVED");
        var first = transaction.execute(status ->
                service.create(payload(payment, p -> p), RefundReason.CANCELED, RefundSource.EVENT, UUID.randomUUID()));
        jdbc.update("UPDATE payments SET status = 'REFUNDED', refunded_at = now() WHERE id = ?", payment.paymentId());

        var second = transaction.execute(status ->
                service.create(payload(payment, p -> p), RefundReason.CANCELED, RefundSource.DLQ, UUID.randomUUID()));

        var duplicate = assertInstanceOf(RefundCreation.Duplicate.class, second);
        assertEquals(((RefundCreation.Created) first).refundId(), duplicate.refundId());
        assertEquals(1, fixtures.refundCount(payment.paymentId()));
        assertEquals(1, fixtures.refundHistoryCount(payment.paymentId()));
    }

    @Test
    void 승인되지_않았고_환불도_없으면_NotApproved이고_아무것도_쓰지_않는다() {
        var payment = fixtures.payment("REQUESTED");

        var result = transaction.execute(status ->
                service.create(payload(payment, p -> p), RefundReason.CANCELED, RefundSource.DLQ, UUID.randomUUID()));

        assertInstanceOf(RefundCreation.NotApproved.class, result);
        assertEquals(0, fixtures.refundCount(payment.paymentId()));
        assertEquals(0, fixtures.refundHistoryCount(payment.paymentId()));
    }

    private static RefundRequestedPayload payload(CreatedPayment payment, UnaryOperator<RefundRequestedPayload> change) {
        return change.apply(new RefundRequestedPayload(payment.reservationId(), payment.paymentId(), RefundFixtures.USER_ID,
                BigDecimal.valueOf(RefundFixtures.AMOUNT), "KRW", "CANCELED"));
    }
}
