package com.iseonjwa.payment.pg.infrastructure;

import com.iseonjwa.payment.pg.application.ApproveCommand;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgIdempotencyKey;
import com.iseonjwa.payment.pg.application.PgOrderId;
import com.iseonjwa.payment.pg.application.UnknownCause;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MockPgAdapter}를 실제 mock-pg 앱에 돌리는 계약 테스트(mock-pg.md 10절 단계 0 스파이크).
 * 응답 유실 방식(Valve)과 bootJar 별도 프로세스 기반이 동작하는지 먼저 확인한다.
 *
 * <p>TODO: ISE-102(PR 1)에서 공통 계약 {@code PgClientContractTest}를 상속하고, 같은 항목을 검사하는 이 클래스의
 * 테스트는 정리한다. 경과 시간 단정은 공통 계약에 없으므로 남긴다.
 */
class MockPgServerContractTest {
    // Note: 응답 유실이 연결이 끊겨서가 아니라 읽기 시한이 지나서 CONNECTION_LOST가 되는 경우를 걸러내려고
    // 경과 시간을 이 값과 비교한다. 첫 호출의 클라이언트 준비 시간이 끼어도 넉넉하게 1초로 둔다
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(1);

    private static MockPgProcess server;
    private static PgClient client;

    @BeforeAll
    static void startServer() {
        server = MockPgProcess.start();
        client = MockPgAdapter.create(server.baseUrl(), Duration.ofMillis(500), READ_TIMEOUT);
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.close();
    }

    // 테스트가 장애를 소비하기 전에 실패해도 남은 설정이 다음 테스트의 호출을 바꾸지 않게 한다
    @AfterEach
    void clearFaults() {
        server.applyFault("APPROVE", "NONE", null);
    }

    @Test
    void 처음_보는_주문을_조회하면_NotFound다() {
        assertInstanceOf(PgClient.NotFound.class, client.findPayment(newOrder()));
    }

    @Test
    void 응답이_유실되면_읽기_시한을_기다리지_않고_CONNECTION_LOST인_Unknown이다() {
        server.applyFault("APPROVE", "DROP_RESPONSE", 1);

        var startedAt = System.nanoTime();
        var result = client.approve(approveCommand(newOrder()));
        var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertEquals(new PgClient.Unknown(UnknownCause.CONNECTION_LOST), result);
        assertTrue(elapsed.compareTo(READ_TIMEOUT) < 0, "읽기 시한이 지나서 끊겼다 elapsed=" + elapsed);
    }

    @Test
    void 응답을_잃은_승인은_조회로_확인한다() {
        var order = newOrder();
        server.applyFault("APPROVE", "DROP_RESPONSE", 1);
        assertEquals(new PgClient.Unknown(UnknownCause.CONNECTION_LOST), client.approve(approveCommand(order)));

        var found = assertInstanceOf(PgClient.Approved.class, client.findPayment(order));

        assertTrue(found.matches(BigDecimal.valueOf(50000), "KRW"));
    }

    private static PgOrderId newOrder() {
        return new PgOrderId(UUID.randomUUID());
    }

    private static ApproveCommand approveCommand(PgOrderId order) {
        return new ApproveCommand(order, BigDecimal.valueOf(50000), "KRW", PgIdempotencyKey.ofApproval(order), null);
    }
}
