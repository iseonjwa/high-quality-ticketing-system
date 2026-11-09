package com.iseonjwa.payment.pg.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * 결제 토큰에서 PG 주문 식별자(`payments.pg_order_id`)를 결정적으로 만든다. 결제창을 열기 전(checkout API)과 TX1이
 * 같은 토큰에서 같은 값을 얻으므로 저장 없이 토스 `orderId`를 미리 정할 수 있다(ADR 0023 개정).
 *
 * <p>Note: 이름 기반 UUID(v3)라 예측 가능하지만 비밀이 아니다. 보안은 결제 토큰의 서명과 `paymentKey`가 맡는다.
 * 토큰이 만료되면 {@code exp}가 달라지므로 새 토큰은 새 값을 얻지만, 예매당 결제는 하나라서
 * (ADR 0026) 새 값이 두 번째 결제로 이어지지 않는다.
 * 빌링(추첨 자동결제) 경로는 토큰이 없어 이 규칙을 쓰지 않고 무작위 UUID를 그대로 쓴다.
 */
public final class PgOrderIds {
    private PgOrderIds() {}

    public static PgOrderId of(long reservationId, Instant tokenExpiresAt) {
        var name = "pg-order:" + reservationId + ":" + tokenExpiresAt.getEpochSecond();
        return new PgOrderId(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)));
    }
}
