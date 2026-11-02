package com.iseonjwa.mockpg.ledger;

import com.iseonjwa.mockpg.ledger.OrderState.Approved;
import com.iseonjwa.mockpg.ledger.OrderState.Canceled;
import com.iseonjwa.mockpg.ledger.OrderState.Decided;
import com.iseonjwa.mockpg.ledger.OrderState.InFlight;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Mock PG 원장 한 세대. 주문 상태와 승인 거래 색인을 메모리에만 둔다(mock-pg.md 2절). 초기화는 이 객체를 비우지 않고
 * {@link LedgerHolder}가 새 객체로 바꾼다.
 *
 * <p>Note: 주문 하나의 상태는 {@code orders}의 compute 계열 연산으로만 바꾼다. {@code ConcurrentHashMap}은 키가 속한 bin 단위로
 * 잠그므로 전역 락과 달리 서로 다른 주문이 거의 막히지 않는다.
 */
public final class Ledger {
    private final Map<String, OrderState> orders = new ConcurrentHashMap<>();
    // 승인 금액·통화를 복사해 둔 색인. 환불이 orders를 다시 잠그지 않게 한다(잠금 순서가 거꾸로면 교착 위험)
    private final Map<String, Approved> paymentsByTx = new ConcurrentHashMap<>();

    /**
     * 승인을 처리한다. 처음 보는 주문이면 {@code IN_FLIGHT}로 표시하고, {@code decision}이 정한 결과로 확정해 돌려준다.
     * 이미 상태가 있으면 {@code decision}을 부르지 않고 현재 상태를 돌려준다. 다른 시도가 처리 중이면 {@link InFlight},
     * 확정됐으면 그 결과, 망취소됐으면 {@link Canceled}다.
     *
     * <p>{@code decision}은 잠금 밖에서 실행한다. 승인 지연(장애 주입 DELAY)이 다른 요청을 막지 않게 하려는 것이다.
     * {@code decision}이 예외로 끝나면 자기 시도의 {@code IN_FLIGHT}를 지운다. 승인한 적이 없으므로 "없음"이 사실과 맞다.
     */
    public OrderState approve(String orderId, Supplier<? extends Decided> decision) {
        var attempt = InFlight.newAttempt();
        var current = orders.compute(orderId, (id, existing) -> existing == null ? attempt : existing);
        if (current != attempt) return current;

        var settled = false;
        try {
            var result = Objects.requireNonNull(decision.get(), "승인 결과가 없다");
            settle(orderId, attempt, result);
            settled = true;
            return result;
        } finally {
            if (!settled) abandon(orderId, attempt);
        }
    }

    /**
     * 늦은 승인을 막는다(망취소). 처음 보는 주문만 {@code CANCELED}로 바꾸고, 그 밖에는 상태를 바꾸지 않은 채 현재 상태를 돌려준다.
     * 처리 중이면 취소를 보장할 수 없어 바꾸지 않는다. 거절은 저장된 거절을 재요청에 그대로 돌려줘야 하므로 바꾸지 않는다.
     */
    public OrderState cancel(String orderId) {
        return orders.compute(orderId, (id, existing) -> existing == null ? Canceled.INSTANCE : existing);
    }

    public Optional<OrderState> find(String orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }

    public Optional<Approved> findApproved(String pgTxId) {
        return Optional.ofNullable(paymentsByTx.get(pgTxId));
    }

    // Note: 현재 값이 자기 시도일 때만 확정한다. 망취소는 IN_FLIGHT를 바꾸지 않으므로 CANCELED를 응답한 주문이 뒤늦게
    // APPROVED가 될 수 없다(INV-8). 승인 색인은 같은 compute 안에서 넣는다. 나중에 넣으면 조회가 pgTxId를 보여 준
    // 직후 환불이 그 결제를 찾지 못하는 틈이 생긴다
    private void settle(String orderId, InFlight attempt, Decided result) {
        orders.compute(orderId, (id, current) -> {
            if (current != attempt) {
                throw new IllegalStateException("현재 값이 이 승인 시도가 아니다 orderId=" + id);
            }
            if (result instanceof Approved approved) paymentsByTx.put(approved.pgTxId(), approved);
            return result;
        });
    }

    private void abandon(String orderId, InFlight attempt) {
        orders.computeIfPresent(orderId, (id, current) -> current == attempt ? null : current);
    }
}
