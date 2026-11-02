package com.iseonjwa.mockpg.payment;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 스파이크용 최소 원장. 승인만 주문별로 한 번 저장한다.
 *
 * <p>TODO: ISE-102(PR 1)에서 mock-pg.md 2~4절의 원장(IN_FLIGHT 시도 토큰, 거절·망취소 상태, 호출 계수,
 * reset 때 원장 교체)으로 바꾼다.
 */
@Component
class PaymentLedger {
    private final Map<String, ApprovedPayment> approvals = new ConcurrentHashMap<>();

    // 같은 주문의 재요청은 처음 저장한 승인을 그대로 돌려준다(mock-pg.md 3절)
    ApprovedPayment approve(String orderId, BigDecimal amount, String currency) {
        return approvals.computeIfAbsent(orderId,
                id -> new ApprovedPayment(id, "pg-" + UUID.randomUUID(), amount, currency, Instant.now()));
    }

    Optional<ApprovedPayment> find(String orderId) {
        return Optional.ofNullable(approvals.get(orderId));
    }
}
