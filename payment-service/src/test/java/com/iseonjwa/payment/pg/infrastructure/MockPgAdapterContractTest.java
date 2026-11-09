package com.iseonjwa.payment.pg.infrastructure;

import com.iseonjwa.payment.pg.application.ApproveCommand;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgIdempotencyKey;
import com.iseonjwa.payment.pg.application.PgOrderId;
import com.iseonjwa.payment.pg.application.UnknownCause;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 실제 mock-pg 서버로는 만들 수 없는 상황만 확인하는 어댑터 고유 테스트(mock-pg.md 1절). 공통 계약
 * {@code PgClientContractTest}와 이관한 어댑터 고유 테스트는 실제 서버를 쓰는 {@link MockPgServerContractTest}가 맡는다.
 *
 * <ul>
 *   <li>본문 코드가 없는 404: 실제 앱은 항상 코드를 담아 답하므로 가짜 서버({@link FakeMockPgServer})가 필요하다.</li>
 *   <li>연결 불가: 서버가 필요 없다.</li>
 * </ul>
 */
class MockPgAdapterContractTest {
    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);
    private static final Duration READ_TIMEOUT = Duration.ofMillis(300);

    @Test
    void 해당_주문_없음_코드가_없는_404는_NotFound가_아니라_CLIENT_ERROR인_Unknown이다() {
        try (var server = new FakeMockPgServer()) {
            var client = MockPgAdapter.create(server.baseUrl(), CONNECT_TIMEOUT, READ_TIMEOUT);

            assertEquals(new PgClient.Unknown(UnknownCause.CLIENT_ERROR), client.findPayment(newOrder()));
        }
    }

    @Test
    void 연결할_수_없으면_CONNECTION_LOST인_Unknown이다() {
        var unreachable = MockPgAdapter.create("http://127.0.0.1:1", CONNECT_TIMEOUT, READ_TIMEOUT);

        var order = newOrder();
        var result = unreachable.approve(new ApproveCommand(order, BigDecimal.valueOf(50000), "KRW",
                PgIdempotencyKey.ofApproval(order), null));

        assertEquals(new PgClient.Unknown(UnknownCause.CONNECTION_LOST), result);
    }

    private static PgOrderId newOrder() {
        return new PgOrderId(UUID.randomUUID());
    }
}
