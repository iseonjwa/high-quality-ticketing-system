package com.iseonjwa.mockpg.ledger;

import com.iseonjwa.mockpg.ledger.OrderState.Approved;
import com.iseonjwa.mockpg.ledger.OrderState.Canceled;
import com.iseonjwa.mockpg.ledger.OrderState.Decided;
import com.iseonjwa.mockpg.ledger.OrderState.InFlight;
import com.iseonjwa.mockpg.ledger.RefundOutcome.Refunded;
import com.iseonjwa.mockpg.ledger.RefundOutcome.RejectCode;
import com.iseonjwa.mockpg.ledger.RefundOutcome.Rejected;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
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
    // 승인 금액·통화를 복사해 둔 색인과 환불 누계. 환불이 orders를 다시 잠그지 않게 한다(잠금 순서가 거꾸로면 교착 위험)
    private final Map<String, PaymentEntry> paymentsByTx = new ConcurrentHashMap<>();
    // 성공한 환불만 저장한다. 거절은 저장하지 않는다(mock-pg.md 8절)
    private final Map<String, Refunded> refunds = new ConcurrentHashMap<>();

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
        return Optional.ofNullable(paymentsByTx.get(pgTxId)).map(PaymentEntry::approved);
    }

    /**
     * 환불을 판정하고 성공이면 저장한다(mock-pg.md 8절). 호출한 쪽이 {@code refundTxId}·{@code refundedAt}까지 채운 후보를 넘긴다.
     * 잠금 안에서 UUID나 시계를 부르지 않으려는 것이다.
     *
     * <p>판정 순서: 같은 {@code refundId}의 저장된 결과가 있으면 그 결과(결제가 다르면 {@code OTHER}), 승인된 결제가 없으면
     * {@code NOT_REFUNDABLE}, 통화가 다르거나 환불 합계가 승인 금액을 넘으면 {@code NOT_REFUNDABLE}, 그 밖에는 저장한다.
     *
     * <p>Note: 같은 {@code pgTxId}의 요청은 같은 키의 {@code compute}로 줄 세워지므로, 저장된 환불을 확인한 뒤 저장하기 전에
     * 같은 결제의 다른 요청이 끼어들 수 없다. 같은 {@code refundId}가 다른 {@code pgTxId}로 동시에 오면 서로 다른 키라
     * {@code compute}로는 줄 세워지지 않으므로, {@code putIfAbsent}가 저장 시점을 하나로 정한다. 진 쪽은 누계를 늘리지 않는다.
     * 잠금 순서는 승인이 orders→paymentsByTx, 환불이 paymentsByTx→refunds라 거꾸로 잡는 경로가 없다.
     */
    public RefundOutcome refund(Refunded candidate) {
        var outcome = new AtomicReference<RefundOutcome>();
        paymentsByTx.compute(candidate.pgTxId(), (pgTxId, entry) -> {
            var stored = refunds.get(candidate.refundId());
            if (stored != null) {
                outcome.set(replay(stored, candidate));
                return entry;
            }
            // 항목이 없을 때 null을 돌려주면 맵에 아무것도 생기지 않는다
            if (entry == null) {
                outcome.set(new Rejected(RejectCode.NOT_REFUNDABLE));
                return null;
            }
            if (!entry.canRefund(candidate)) {
                outcome.set(new Rejected(RejectCode.NOT_REFUNDABLE));
                return entry;
            }
            var raced = refunds.putIfAbsent(candidate.refundId(), candidate);
            if (raced != null) {
                outcome.set(replay(raced, candidate));
                return entry;
            }
            outcome.set(candidate);
            return entry.withRefunded(candidate.amount());
        });
        return outcome.get();
    }

    public Optional<Refunded> findRefund(String refundId) {
        return Optional.ofNullable(refunds.get(refundId));
    }

    // 같은 refundId의 재요청. 같은 결제면 저장된 결과를, 다른 결제면 OTHER를 돌려주고 항목은 그대로 둔다
    private static RefundOutcome replay(Refunded stored, Refunded request) {
        return stored.pgTxId().equals(request.pgTxId()) ? stored : new Rejected(RejectCode.OTHER);
    }

    // Note: 현재 값이 자기 시도일 때만 확정한다. 망취소는 IN_FLIGHT를 바꾸지 않으므로 CANCELED를 응답한 주문이 뒤늦게
    // APPROVED가 될 수 없다(INV-8). 승인 색인은 같은 compute 안에서 넣는다. 나중에 넣으면 조회가 pgTxId를 보여 준
    // 직후 환불이 그 결제를 찾지 못하는 틈이 생긴다
    private void settle(String orderId, InFlight attempt, Decided result) {
        orders.compute(orderId, (id, current) -> {
            if (current != attempt) {
                throw new IllegalStateException("현재 값이 이 승인 시도가 아니다 orderId=" + id);
            }
            if (result instanceof Approved approved) {
                paymentsByTx.put(approved.pgTxId(), new PaymentEntry(approved, BigDecimal.ZERO));
            }
            return result;
        });
    }

    private void abandon(String orderId, InFlight attempt) {
        orders.computeIfPresent(orderId, (id, current) -> current == attempt ? null : current);
    }

    /** 승인 결제와 지금까지 환불한 금액의 합. 불변이고 환불마다 새 객체로 바꾼다. */
    private record PaymentEntry(Approved approved, BigDecimal refundedTotal) {
        boolean canRefund(Refunded request) {
            return approved.currency().equals(request.currency())
                    && refundedTotal.add(request.amount()).compareTo(approved.amount()) <= 0;
        }

        PaymentEntry withRefunded(BigDecimal amount) {
            return new PaymentEntry(approved, refundedTotal.add(amount));
        }
    }
}
