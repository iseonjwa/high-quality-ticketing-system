package com.iseonjwa.payment.pg.infrastructure;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.iseonjwa.payment.payment.domain.DeclineReason;
import com.iseonjwa.payment.pg.PgClientContractTest;
import com.iseonjwa.payment.pg.PgScenario;
import com.iseonjwa.payment.pg.application.ApproveCommand;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgClient.Approved;
import com.iseonjwa.payment.pg.application.PgClient.Declined;
import com.iseonjwa.payment.pg.application.PgClient.NotFound;
import com.iseonjwa.payment.pg.application.PgClient.Unknown;
import com.iseonjwa.payment.pg.application.PgIdempotencyKey;
import com.iseonjwa.payment.pg.application.RefundRejectReason;
import com.iseonjwa.payment.pg.application.UnknownCause;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static com.iseonjwa.payment.pg.infrastructure.FakeTossServer.Fault;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Note: 가짜 토스 서버(JDK HttpServer)에 붙는다. 실제 토스 호출은 toss-sandbox 태그 테스트가 맡는다.
// 토스는 늦은 승인을 막는 API가 없어 늦은 승인 방지는 Prevented 대신 Unknown을 기대한다(ADR 0023 개정).
class TossPgAdapterContractTest extends PgClientContractTest {
    // 가짜 서버와 어댑터가 주고받는 시험용 값이다. 실제 키가 아니다
    private static final String SECRET_KEY = "test_sk_unit_test_dummy_0123456789";
    // Note: 읽기 시한 300ms에서는 CI 러너가 잠깐 느려지면 정상 승인도 TIMEOUT이 됐다(ISE-142). 실제 mock-pg 계약
    // (MockPgServerContractTest)과 같은 1초·2.5초로 둔다. 느린 응답은 시한보다 충분히 길어야 한다
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration SLOW_DELAY = Duration.ofMillis(2_500);

    private static FakeTossServer server;
    private static PgClient client;

    private final PgScenario scenario = new PgScenario() {
        public void nextApprovalIsDeclined() { server.failNextConfirm(Fault.DECLINE); }
        public void nextApprovalIsSlow() { server.delayNextConfirm(SLOW_DELAY.toMillis()); }
        public void nextApprovalResponseIsLost() { server.failNextConfirm(Fault.DROP_RESPONSE); }
        public void nextApprovalIsProcessing() { server.failNextConfirm(Fault.PROCESSING); }
        public void nextApprovalIsBadRequest() { server.failNextConfirm(Fault.BAD_REQUEST); }
        public void nextApprovalIsServerError() { server.failNextConfirm(Fault.SERVER_ERROR); }
        public void nextApprovalHasFractionalAmount() { server.failNextConfirm(Fault.FRACTIONAL_AMOUNT); }
        public void nextRefundOmitsAmountAndCurrency() { server.failNextCancel(Fault.MISSING_AMOUNT); }
        public void awaitPendingEffects() { sleep(SLOW_DELAY.toMillis() + 300); }
        public void nextApprovalOmitsAmountAndCurrency() { server.failNextConfirm(Fault.MISSING_AMOUNT); }
        public void nextApprovalUsesDifferentAmount() { server.failNextConfirm(Fault.WRONG_AMOUNT); }
        public void nextRefundIsRejected() { server.failNextCancel(Fault.REJECT); }
        public void nextRefundResponseIsLost() { server.failNextCancel(Fault.DROP_RESPONSE); }
        public void nextRefundIsProcessing() { server.failNextCancel(Fault.PROCESSING); }
    };

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger adapterLogger = (Logger) LoggerFactory.getLogger(TossPgAdapter.class);

    @BeforeAll
    static void startServer() {
        server = new FakeTossServer(SECRET_KEY);
        client = TossPgAdapter.create(server.baseUrl(), SECRET_KEY, Duration.ofMillis(500), READ_TIMEOUT);
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @BeforeEach
    void captureLogs() {
        adapterLogger.setLevel(Level.DEBUG);
        logs.start();
        adapterLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        adapterLogger.detachAppender(logs);
        logs.stop();
    }

    @Override
    protected PgClient client() {
        return client;
    }

    @Override
    protected PgScenario scenario() {
        return scenario;
    }

    @Override
    protected boolean guaranteesLateApprovalPrevention() {
        return false;
    }

    @Test
    void 요청은_시크릿_키에_콜론을_붙여_Base64로_인코딩한_Basic_헤더로_인증한다() {
        var order = newOrder();

        client().approve(approveCommand(order));

        var expected = "Basic " + Base64.getEncoder().encodeToString((SECRET_KEY + ":").getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, server.authorizations().getLast());
    }

    @Test
    void 승인은_paymentKey_orderId_금액을_본문으로_보내고_멱등키는_주문에서_파생한다() {
        var order = newOrder();

        client().approve(approveCommand(order));

        var request = server.lastConfirmRequest();
        assertEquals(clientPaymentRef(order).value(), request.get("paymentKey").asString());
        assertEquals(order.value().toString(), request.get("orderId").asString());
        assertEquals(50000, request.get("amount").asInt());
        assertEquals(order.value().toString(), server.confirmIdempotencyKey(order.value().toString()));
    }

    @Test
    void 같은_주문을_여러_번_승인해도_토스_원장에는_한_번만_남는다() {
        var order = newOrder();

        client().approve(approveCommand(order));
        client().approve(approveCommand(order));

        assertEquals(1, server.confirmCount(order.value().toString()));
    }

    @Test
    void 잘못된_시크릿_키는_거절이_아니라_CLIENT_ERROR인_Unknown이다() {
        var wrongKey = TossPgAdapter.create(server.baseUrl(), "test_sk_wrong_key", Duration.ofMillis(500), READ_TIMEOUT);

        var result = wrongKey.approve(approveCommand(newOrder()));

        assertEquals(new Unknown(UnknownCause.CLIENT_ERROR), result);
    }

    @Test
    void clientPaymentRef가_없으면_토스를_호출하지_않고_CLIENT_ERROR인_Unknown이다() {
        var order = newOrder();
        var withoutRef = new ApproveCommand(order, AMOUNT, KRW, PgIdempotencyKey.ofApproval(order), null);
        var calls = server.authorizations().size();

        var result = client().approve(withoutRef);

        assertEquals(new Unknown(UnknownCause.CLIENT_ERROR), result);
        assertEquals(calls, server.authorizations().size());
    }

    @Test
    void PROVIDER_ERROR는_4xx지만_일시_오류라_PG_ERROR인_Unknown이다() {
        server.failNextConfirm(Fault.PROVIDER_ERROR);

        assertEquals(new Unknown(UnknownCause.PG_ERROR), client().approve(approveCommand(newOrder())));
    }

    @Test
    void 이미_처리된_결제_오류는_거절이_아니라_PROCESSING인_Unknown이다() {
        server.failNextConfirm(Fault.ALREADY_PROCESSED);

        assertEquals(new Unknown(UnknownCause.PROCESSING), client().approve(approveCommand(newOrder())));
    }

    @Test
    void 다른_멱등키로_이미_승인된_주문을_다시_승인하면_PROCESSING인_Unknown이다() {
        var order = newOrder();
        client().approve(approveCommand(order));
        var again = new ApproveCommand(order, AMOUNT, KRW, new PgIdempotencyKey("another-key"), clientPaymentRef(order));

        assertEquals(new Unknown(UnknownCause.PROCESSING), client().approve(again));
    }

    @Test
    void 결제_세션이_없으면_Declined다() {
        server.failNextConfirm(Fault.SESSION_EXPIRED);

        var declined = assertInstanceOf(Declined.class, client().approve(approveCommand(newOrder())));

        assertEquals(DeclineReason.OTHER, declined.reason());
    }

    @Test
    void 모르는_오류_코드의_4xx는_CLIENT_ERROR인_Unknown이다() {
        server.failNextConfirm(Fault.UNKNOWN_ERROR_CODE);

        assertEquals(new Unknown(UnknownCause.CLIENT_ERROR), client().approve(approveCommand(newOrder())));
    }

    @Test
    void 응답의_paymentKey가_요청과_다르면_승인으로_받지_않고_PG_ERROR인_Unknown이다() {
        server.failNextConfirm(Fault.WRONG_PAYMENT_KEY);

        assertEquals(new Unknown(UnknownCause.PG_ERROR), client().approve(approveCommand(newOrder())));
    }

    @Test
    void 연결할_수_없으면_CONNECTION_LOST인_Unknown이다() {
        var unreachable = TossPgAdapter.create("http://127.0.0.1:1", SECRET_KEY, Duration.ofMillis(500), READ_TIMEOUT);

        assertEquals(new Unknown(UnknownCause.CONNECTION_LOST), unreachable.approve(approveCommand(newOrder())));
    }

    @Test
    void 만료된_결제_조회는_NotFound다() {
        var order = newOrder();
        client().approve(approveCommand(order));
        server.forceStatus(order.value().toString(), "EXPIRED");

        assertInstanceOf(NotFound.class, client().findPayment(order));
    }

    @Test
    void 인증만_끝난_결제_조회는_PROCESSING인_Unknown이다() {
        var order = newOrder();
        server.seedInProgress(order.value().toString(), clientPaymentRef(order).value());

        assertEquals(new Unknown(UnknownCause.PROCESSING), client().findPayment(order));
    }

    @Test
    void 취소된_결제_조회는_PG_ERROR인_Unknown이고_경고를_남긴다() {
        var order = newOrder();
        client().approve(approveCommand(order));
        server.forceStatus(order.value().toString(), "CANCELED");

        assertEquals(new Unknown(UnknownCause.PG_ERROR), client().findPayment(order));
        assertTrue(logs.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                && event.getFormattedMessage().contains("CANCELED")));
    }

    @Test
    void 승인_뒤_늦은_승인_방지를_물으면_DONE이라_승인_사실을_알린다() {
        var order = newOrder();
        var approved = assertInstanceOf(Approved.class, client().approve(approveCommand(order)));

        var already = assertInstanceOf(PgClient.AlreadyApproved.class, client().preventLateApproval(order));

        assertEquals(approved.paymentRef(), already.paymentRef());
    }

    @Test
    void 환불은_cancelAmount를_명시하고_멱등키는_환불_식별자에서_파생한다() {
        var approved = assertInstanceOf(Approved.class, client().approve(approveCommand(newOrder())));
        var refundId = newRefund();

        client().refund(refundCommand(approved, refundId));

        var request = server.lastCancelRequest();
        assertEquals(50000, request.get("cancelAmount").asInt());
        assertFalse(request.get("cancelReason").asString().isBlank());
        assertEquals(1, server.cancelCount(refundId.value().toString()));
    }

    @Test
    void 같은_환불_식별자로_여러_번_보내도_토스_원장에는_한_번만_남는다() {
        var approved = assertInstanceOf(Approved.class, client().approve(approveCommand(newOrder())));
        var refundId = newRefund();

        client().refund(refundCommand(approved, refundId));
        client().refund(refundCommand(approved, refundId));

        assertEquals(1, server.cancelCount(refundId.value().toString()));
    }

    @Test
    void 이미_취소된_결제를_다른_환불_식별자로_환불하면_RefundRejected다() {
        var approved = assertInstanceOf(Approved.class, client().approve(approveCommand(newOrder())));
        client().refund(refundCommand(approved, newRefund()));

        var second = client().refund(refundCommand(approved, newRefund()));

        assertEquals(new PgClient.RefundRejected(RefundRejectReason.NOT_REFUNDABLE),
                second);
    }

    @Test
    void 빌링은_지원하지_않아_빌링키_발급은_CLIENT_ERROR_승인은_보내지_않은_거절이다() {
        var order = newOrder();
        var calls = server.authorizations().size();

        assertEquals(new Unknown(UnknownCause.CLIENT_ERROR), client().issueBillingKey("auth", "customer"));
        assertEquals(new Declined(DeclineReason.NOT_SENT), client().approveBilling(approveCommand(order), "billing"));
        assertEquals(calls, server.authorizations().size());
    }

    @Test
    void 실패_경로의_로그와_예외에_시크릿_키가_남지_않는다() {
        var wrongKey = TossPgAdapter.create(server.baseUrl(), "test_sk_wrong_key", Duration.ofMillis(500), READ_TIMEOUT);
        var unreachable = TossPgAdapter.create("http://127.0.0.1:1", SECRET_KEY, Duration.ofMillis(500), READ_TIMEOUT);
        server.failNextConfirm(Fault.SERVER_ERROR);
        client().approve(approveCommand(newOrder()));
        server.delayNextConfirm(SLOW_DELAY.toMillis());
        client().approve(approveCommand(newOrder()));
        wrongKey.approve(approveCommand(newOrder()));
        unreachable.approve(approveCommand(newOrder()));
        unreachable.findPayment(newOrder());

        var basic = Base64.getEncoder().encodeToString((SECRET_KEY + ":").getBytes(StandardCharsets.UTF_8));
        assertFalse(logs.list.isEmpty());
        for (var event : logs.list) {
            var text = event.getFormattedMessage() + ThrowableProxyUtil.asString(event.getThrowableProxy());
            assertFalse(text.contains(SECRET_KEY), "로그에 시크릿 키가 남았다: " + text);
            assertFalse(text.contains(basic), "로그에 Authorization 값이 남았다: " + text);
            assertFalse(text.contains("test_sk_wrong_key"), "로그에 잘못된 시크릿 키가 남았다: " + text);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
