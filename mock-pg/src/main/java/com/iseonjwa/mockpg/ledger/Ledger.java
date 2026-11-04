package com.iseonjwa.mockpg.ledger;

import com.iseonjwa.mockpg.ledger.LedgerViews.IdempotencyKeys;
import com.iseonjwa.mockpg.ledger.LedgerViews.OrderPage;
import com.iseonjwa.mockpg.ledger.LedgerViews.OrderRef;
import com.iseonjwa.mockpg.ledger.LedgerViews.OrderView;
import com.iseonjwa.mockpg.ledger.LedgerViews.PaymentRefundsView;
import com.iseonjwa.mockpg.ledger.LedgerViews.RefundView;
import com.iseonjwa.mockpg.ledger.LedgerViews.Summary;
import com.iseonjwa.mockpg.ledger.OrderState.Approved;
import com.iseonjwa.mockpg.ledger.OrderState.Canceled;
import com.iseonjwa.mockpg.ledger.OrderState.Decided;
import com.iseonjwa.mockpg.ledger.OrderState.Declined;
import com.iseonjwa.mockpg.ledger.OrderState.InFlight;
import com.iseonjwa.mockpg.ledger.RefundOutcome.Refunded;
import com.iseonjwa.mockpg.ledger.RefundOutcome.RejectCode;
import com.iseonjwa.mockpg.ledger.RefundOutcome.Rejected;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
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
    // 주문 하나의 상태와 호출 계수를 한 항목에 묶어, 상태 변경과 계수 갱신이 같은 compute 안에서 일어나게 한다
    private final Map<String, OrderEntry> orders = new ConcurrentHashMap<>();
    // 주문이 처음 보인 순서(단조 증가 순번) 색인. 목록 조회의 cursor 기준이다. 맵 순회 순서에 기대지 않으려는 것이다
    private final ConcurrentSkipListMap<Long, String> orderIdsBySeq = new ConcurrentSkipListMap<>();
    private final AtomicLong nextOrderSeq = new AtomicLong();
    // 승인 금액·통화를 복사해 둔 색인과 환불 누계. 환불이 orders를 다시 잠그지 않게 한다(잠금 순서가 거꾸로면 교착 위험)
    private final Map<String, PaymentEntry> paymentsByTx = new ConcurrentHashMap<>();
    // 성공한 환불만 저장한다. 거절은 저장하지 않는다(mock-pg.md 8절)
    private final Map<String, Refunded> refunds = new ConcurrentHashMap<>();
    // 환불 식별자별 호출 계수. 거절·장애 주입으로 저장되지 않은 호출도 센다(mock-pg.md 2절)
    private final Map<String, RefundCalls> refundCalls = new ConcurrentHashMap<>();
    private final AtomicLong nextRefundSeq = new AtomicLong();

    /**
     * 승인을 처리한다. 처음 보는 주문이면 {@code IN_FLIGHT}로 표시하고, {@code decision}이 정한 결과로 확정해 돌려준다.
     * 이미 상태가 있으면 {@code decision}을 부르지 않고 현재 상태를 돌려준다. 다른 시도가 처리 중이면 {@link InFlight},
     * 확정됐으면 그 결과, 망취소됐으면 {@link Canceled}다.
     *
     * <p>호출 계수와 {@code Idempotency-Key} 기록은 상태를 정하는 같은 {@code compute} 안에서 갱신한다. 응답이 409든
     * 저장된 결과든 센다.
     *
     * <p>{@code decision}은 잠금 밖에서 실행한다. 승인 지연(장애 주입 DELAY)이 다른 요청을 막지 않게 하려는 것이다.
     * {@code decision}이 예외로 끝나면 자기 시도의 {@code IN_FLIGHT}를 지운다. 승인한 적이 없으므로 "없음"이 사실과 맞다.
     * 호출 계수는 지우지 않는다. 호출은 실제로 있었기 때문이다.
     */
    public OrderState approve(String orderId, String idempotencyKey, Supplier<? extends Decided> decision) {
        var attempt = InFlight.newAttempt();
        var seen = new AtomicReference<OrderState>();
        orders.compute(orderId, (id, existing) -> {
            var entry = entryOrNew(id, existing).counted(OrderCall.APPROVE, idempotencyKey);
            if (entry.state() != null) {
                seen.set(entry.state());
                return entry;
            }
            seen.set(attempt);
            return entry.withState(attempt);
        });
        if (seen.get() != attempt) return seen.get();

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

    /** 헤더 기록이 필요 없는 호출용. */
    public OrderState approve(String orderId, Supplier<? extends Decided> decision) {
        return approve(orderId, null, decision);
    }

    /**
     * 늦은 승인을 막는다(망취소). 처음 보는 주문만 {@code CANCELED}로 바꾸고, 그 밖에는 상태를 바꾸지 않은 채 현재 상태를 돌려준다.
     * 처리 중이면 취소를 보장할 수 없어 바꾸지 않는다. 거절은 저장된 거절을 재요청에 그대로 돌려줘야 하므로 바꾸지 않는다.
     */
    public OrderState cancel(String orderId) {
        var seen = new AtomicReference<OrderState>();
        orders.compute(orderId, (id, existing) -> {
            var entry = entryOrNew(id, existing).counted(OrderCall.CANCEL, null);
            if (entry.state() == null) entry = entry.withState(Canceled.INSTANCE);
            seen.set(entry.state());
            return entry;
        });
        return seen.get();
    }

    /** 주문 조회 호출. 상태를 돌려주면서 {@code lookupCalls}를 센다. 상태만 읽으려면 {@link #find}를 쓴다. */
    public Optional<OrderState> lookup(String orderId) {
        var seen = new AtomicReference<OrderState>();
        orders.compute(orderId, (id, existing) -> {
            var entry = entryOrNew(id, existing).counted(OrderCall.LOOKUP, null);
            seen.set(entry.state());
            return entry;
        });
        return Optional.ofNullable(seen.get());
    }

    /**
     * 원장 상태를 건드리지 않고 호출만 센다. 장애 주입(500·202)으로 원장에 닿기 전에 끝나는 경로가 쓴다.
     * 호출 계수는 응답 종류와 상관없이 세어야 하기 때문이다(mock-pg.md 2절).
     */
    public void countCall(OrderCall call, String orderId, String idempotencyKey) {
        orders.compute(orderId, (id, existing) -> entryOrNew(id, existing).counted(call, idempotencyKey));
    }

    /** 계수 없이 상태만 읽는다. 테스트와 조회 API가 호출 계수를 오염시키지 않으려고 쓴다. */
    public Optional<OrderState> find(String orderId) {
        var entry = orders.get(orderId);
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.state());
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

    /** 환불 POST 호출을 센다. 거절·장애 주입으로 저장하지 않는 호출도 센다. 요청이 들어오면 처리 전에 한 번 부른다. */
    public void countRefundCall(String refundId, String idempotencyKey) {
        refundCalls.compute(refundId, (id, existing) -> refundCallsOrNew(existing).countedRefund(idempotencyKey));
    }

    /**
     * 환불 조회 GET 호출을 센다. 환불 조회의 기준 키는 요청 경로의 {@code refundId}뿐이다(조회 시점에는 결제를 알 수 없다).
     * 그래서 설계 문서의 "결제별"이 아니라 환불 식별자별로 센다.
     */
    public void countRefundLookup(String refundId) {
        refundCalls.compute(refundId, (id, existing) -> refundCallsOrNew(existing).countedLookup());
    }

    // 호출을 세지 않고 원장에 직접 저장된 환불(테스트)도 조회가 깨지지 않게 0으로 본다
    private RefundCalls callsOf(String refundId) {
        return refundCalls.getOrDefault(refundId, new RefundCalls(Long.MAX_VALUE, 0, 0, KeyLog.EMPTY));
    }

    private RefundCalls refundCallsOrNew(RefundCalls existing) {
        return existing != null ? existing : new RefundCalls(nextRefundSeq.incrementAndGet(), 0, 0, KeyLog.EMPTY);
    }

    /** 주문의 원장 기록. 호출도 상태도 없는 주문이면 비어 있다. */
    public Optional<OrderView> orderView(String orderId) {
        var entry = orders.get(orderId);
        if (entry == null) return Optional.empty();
        var state = entry.state();
        var approved = state instanceof Approved a ? a : null;
        var payment = approved == null ? null : paymentsByTx.get(approved.pgTxId());
        return Optional.of(new OrderView(orderId, statusOf(state),
                approved == null ? null : approved.pgTxId(),
                approved == null ? null : approved.amount(),
                approved == null ? null : approved.currency(),
                entry.calls().approve(), entry.calls().lookup(), entry.calls().cancel(),
                payment == null ? BigDecimal.ZERO : payment.refundedTotal(),
                entry.calls().keys().view()));
    }

    /**
     * 주문 목록 한 쪽. 주문이 처음 보인 순서(단조 증가 순번)로 {@code cursor}보다 뒤의 주문을 {@code limit}개까지 돌려준다.
     * 저장된 상태가 있는 주문만 담고, {@code status}가 null이 아니면 그 상태의 주문만 담는다.
     *
     * <p>Note: 맵 순회 순서는 변경 중에 불안정해서 cursor로 쓸 수 없다. 순번 색인을 쓰므로 같은 cursor로 부르면 같은 순서다.
     * 순번이 작은 주문이 늦게 색인에 보이는 경쟁이 있어서, 요청이 한창 도는 중의 목록은 대조용으로 믿지 않는다.
     * 대조는 {@code inFlightCount}가 0이 된 뒤에 한다.
     */
    public OrderPage orders(String status, int limit, long cursor) {
        var page = new ArrayList<OrderRef>();
        long lastSeq = cursor;
        boolean more = false;
        for (var seqAndId : orderIdsBySeq.tailMap(cursor, false).entrySet()) {
            var entry = orders.get(seqAndId.getValue());
            if (entry == null || entry.state() == null) continue;
            var entryStatus = statusOf(entry.state());
            if (status != null && !status.equals(entryStatus)) continue;
            if (page.size() == limit) {
                more = true;
                break;
            }
            page.add(new OrderRef(seqAndId.getValue(), entryStatus,
                    entry.state() instanceof Approved approved ? approved.pgTxId() : null));
            lastSeq = seqAndId.getKey();
        }
        return new OrderPage(List.copyOf(page), more ? lastSeq : null);
    }

    /** 한 결제의 환불 기록. 저장된 환불만 담고, 환불을 처음 호출한 순서로 정렬한다. */
    public PaymentRefundsView refundsOf(String pgTxId) {
        var views = refunds.values().stream()
                .filter(refunded -> refunded.pgTxId().equals(pgTxId))
                .sorted(Comparator.comparingLong(refunded -> callsOf(refunded.refundId()).seq()))
                .map(this::refundView)
                .toList();
        var payment = paymentsByTx.get(pgTxId);
        return new PaymentRefundsView(views, views.size(), payment == null ? BigDecimal.ZERO : payment.refundedTotal());
    }

    private RefundView refundView(Refunded refunded) {
        var calls = callsOf(refunded.refundId());
        return new RefundView(refunded.refundId(), refunded.refundTxId(), refunded.amount(), refunded.currency(),
                calls.refund(), calls.lookup(), calls.keys().view());
    }

    /** 원장 전체 요약. 맵을 훑어 계산하므로 요약과 reset 사이에 어긋날 전역 상태가 없다. */
    public Summary summary() {
        long approved = 0, declined = 0, canceled = 0, inFlight = 0;
        var approvedTotal = BigDecimal.ZERO;
        for (var entry : orders.values()) {
            switch (entry.state()) {
                case null -> { }
                case InFlight inFlightState -> inFlight++;
                case Declined declinedState -> declined++;
                case Canceled canceledState -> canceled++;
                case Approved approvedState -> {
                    approved++;
                    approvedTotal = approvedTotal.add(approvedState.amount());
                }
            }
        }
        var refundedTotal = BigDecimal.ZERO;
        for (var refunded : refunds.values()) refundedTotal = refundedTotal.add(refunded.amount());
        return new Summary(approved, declined, canceled, refunds.size(), approvedTotal, refundedTotal, inFlight,
                approved + declined + canceled + inFlight);
    }

    private static String statusOf(OrderState state) {
        return switch (state) {
            case null -> "NONE";
            case InFlight inFlight -> "IN_FLIGHT";
            case Approved approved -> "APPROVED";
            case Declined declined -> "DECLINED";
            case Canceled canceled -> "CANCELED";
        };
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
            if (current == null || current.state() != attempt) {
                throw new IllegalStateException("현재 값이 이 승인 시도가 아니다 orderId=" + id);
            }
            if (result instanceof Approved approved) {
                paymentsByTx.put(approved.pgTxId(), new PaymentEntry(approved, BigDecimal.ZERO));
            }
            return current.withState(result);
        });
    }

    private void abandon(String orderId, InFlight attempt) {
        orders.computeIfPresent(orderId, (id, current) -> current.state() == attempt ? current.withState(null) : current);
    }

    private OrderEntry entryOrNew(String orderId, OrderEntry existing) {
        if (existing != null) return existing;
        var seq = nextOrderSeq.incrementAndGet();
        orderIdsBySeq.put(seq, orderId);
        return new OrderEntry(seq, null, OrderCalls.NONE);
    }

    /**
     * 주문 하나의 원장 항목. 상태가 null이면 호출만 있고 저장된 상태가 없다(장애 주입으로 원장에 닿기 전에 끝났거나, 승인 중 예외로
     * 시도를 지운 경우). 불변이고 compute 안에서 새 객체로 바꾼다.
     */
    private record OrderEntry(long seq, OrderState state, OrderCalls calls) {
        OrderEntry withState(OrderState newState) {
            return new OrderEntry(seq, newState, calls);
        }

        OrderEntry counted(OrderCall call, String idempotencyKey) {
            return new OrderEntry(seq, state, calls.counted(call, idempotencyKey));
        }
    }

    private record OrderCalls(long approve, long lookup, long cancel, KeyLog keys) {
        static final OrderCalls NONE = new OrderCalls(0, 0, 0, KeyLog.EMPTY);

        OrderCalls counted(OrderCall call, String idempotencyKey) {
            return switch (call) {
                case APPROVE -> new OrderCalls(approve + 1, lookup, cancel, keys.recorded(idempotencyKey));
                case LOOKUP -> new OrderCalls(approve, lookup + 1, cancel, keys);
                case CANCEL -> new OrderCalls(approve, lookup, cancel + 1, keys);
            };
        }
    }

    /** {@code Idempotency-Key} 헤더 값의 기록: 마지막 값과 서로 다른 값 모음. */
    private record KeyLog(String last, Set<String> distinct) {
        static final KeyLog EMPTY = new KeyLog(null, Set.of());

        KeyLog recorded(String key) {
            if (key == null) return this;
            var all = new HashSet<>(distinct);
            all.add(key);
            return new KeyLog(key, Set.copyOf(all));
        }

        IdempotencyKeys view() {
            return new IdempotencyKeys(last, distinct.size());
        }
    }

    private record RefundCalls(long seq, long refund, long lookup, KeyLog keys) {
        RefundCalls countedRefund(String idempotencyKey) {
            return new RefundCalls(seq, refund + 1, lookup, keys.recorded(idempotencyKey));
        }

        RefundCalls countedLookup() {
            return new RefundCalls(seq, refund, lookup + 1, keys);
        }
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
