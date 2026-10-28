package com.iseonjwa.payment.pg.infrastructure;

import com.iseonjwa.payment.pg.PgClientContractTest;
import com.iseonjwa.payment.pg.PgScenario;
import com.iseonjwa.payment.pg.application.PgClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.iseonjwa.payment.pg.infrastructure.FakeMockPgServer.Fault;
import static org.junit.jupiter.api.Assertions.assertEquals;

// Note: 응답 시간 제한을 짧게 줘 느린 응답 시나리오가 빨리 끝나게 한다. 운영 값(2초)은 PgProperties 기본값이다.
class MockPgAdapterContractTest extends PgClientContractTest {
    private static final Duration READ_TIMEOUT = Duration.ofMillis(300);

    private static FakeMockPgServer server;
    private static PgClient client;

    private final PgScenario scenario = new PgScenario() {
        public void nextApprovalIsDeclined() { server.failNextApproval(Fault.DECLINE); }
        public void nextApprovalIsSlow() { server.delayNextApproval(READ_TIMEOUT.toMillis() * 4); }
        public void nextApprovalResponseIsLost() { server.failNextApproval(Fault.DROP_RESPONSE); }
        public void nextApprovalIsProcessing() { server.failNextApproval(Fault.PROCESSING); }
        public void nextApprovalIsBadRequest() { server.failNextApproval(Fault.BAD_REQUEST); }
        public void nextApprovalOmitsAmountAndCurrency() { server.failNextApproval(Fault.MISSING_AMOUNT); }
        public void nextApprovalUsesDifferentAmount() { server.failNextApproval(Fault.WRONG_AMOUNT); }
        public void nextRefundIsRejected() { server.rejectNextRefund(); }
        public void nextRefundResponseIsLost() { server.failNextRefund(Fault.DROP_RESPONSE); }
        public void nextRefundIsProcessing() { server.failNextRefund(Fault.PROCESSING); }
    };

    @BeforeAll
    static void startServer() {
        server = new FakeMockPgServer();
        client = MockPgAdapter.create(server.baseUrl(), Duration.ofMillis(500), READ_TIMEOUT);
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @Override
    protected PgClient client() {
        return client;
    }

    @Override
    protected PgScenario scenario() {
        return scenario;
    }

    @Test
    void 같은_주문을_여러_번_승인해도_PG_원장에는_한_번만_남는다() {
        var order = newOrder();
        var before = server.approvalCount();

        client().approve(approveCommand(order));
        client().approve(approveCommand(order));

        assertEquals(1, server.approvalCount() - before);
    }

    @Test
    void 연결할_수_없으면_CONNECTION_LOST인_Unknown이다() {
        var unreachable = MockPgAdapter.create("http://127.0.0.1:1", Duration.ofMillis(500), READ_TIMEOUT);

        var result = unreachable.approve(approveCommand(newOrder()));

        assertEquals(new PgClient.Unknown(com.iseonjwa.payment.pg.application.UnknownCause.CONNECTION_LOST), result);
    }
}
