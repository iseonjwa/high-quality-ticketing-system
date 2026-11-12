package com.iseonjwa.mockpg.payment;

import com.iseonjwa.mockpg.billing.BillingKeySigner;
import com.iseonjwa.mockpg.fault.Fault;
import com.iseonjwa.mockpg.fault.FaultInjector;
import com.iseonjwa.mockpg.fault.FaultMode;
import com.iseonjwa.mockpg.fault.FaultTarget;
import com.iseonjwa.mockpg.ledger.LedgerHolder;
import com.iseonjwa.mockpg.ledger.OrderCall;
import com.iseonjwa.mockpg.ledger.OrderState;
import com.iseonjwa.mockpg.ledger.OrderState.Approved;
import com.iseonjwa.mockpg.ledger.OrderState.Declined;
import com.iseonjwa.mockpg.ledger.TxIdSigner;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mock PG의 승인·망취소·조회. 상태 전이는 원장이 맡고, 이 클래스는 요청마다 적용할 장애를 정해 mock-pg.md 5절 표대로
 * 원장 처리 전후에 끼워 넣는다. 장애 설정은 형식 검증을 통과한 요청에서만 한 번 읽는다. 잘못된 요청이 장애 횟수를 쓰지 않게 하려는 것이다.
 */
@Service
class PaymentService {
    private static final String DECLINE_CODE = "CARD_DECLINED";
    private static final String BILLING_KEY_DECLINE_CODE = "BILLING_KEY_INVALID";
    private static final BigDecimal WRONG_AMOUNT_DIFFERENCE = BigDecimal.valueOf(1000);
    private static final BigDecimal FRACTIONAL_AMOUNT_DIFFERENCE = new BigDecimal("0.5");

    private final LedgerHolder ledgers;
    private final FaultInjector faults;
    private final BillingKeySigner billingKeys;
    private final TxIdSigner txIds;

    PaymentService(LedgerHolder ledgers, FaultInjector faults, BillingKeySigner billingKeys, TxIdSigner txIds) {
        this.ledgers = ledgers;
        this.faults = faults;
        this.billingKeys = billingKeys;
        this.txIds = txIds;
    }

    Outcome approve(ApproveRequest request, String idempotencyKey) {
        // Note: 요청 하나는 처음 얻은 원장만 끝까지 쓴다. 지연 중 reset이 와도 계수가 새 원장에 섞이지 않는다
        var ledger = ledgers.current();
        var fault = faults.claim(FaultTarget.APPROVE, request.orderId());
        if (skipsLedger(fault)) {
            // 장애 주입으로 원장 상태에 닿지 않아도 호출은 센다. 500을 받고 다시 승인을 호출하는 INV-1 위반을 잡으려는 것이다
            ledger.countCall(OrderCall.APPROVE, request.orderId(), idempotencyKey);
            return Outcome.withoutLedger(fault);
        }

        // Note: 새 주문이면 결정 안에서 기다려 지연 동안 원장이 IN_FLIGHT로 남는다.
        // 이미 상태가 있는 주문은 결정을 부르지 않으므로 응답 직전에 기다린다
        var decisionRan = new AtomicBoolean();
        var state = ledger.approve(request.orderId(), idempotencyKey, () -> {
            decisionRan.set(true);
            fault.awaitDelay();
            return decide(request, fault);
        });
        if (!decisionRan.get()) fault.awaitDelay();
        return Outcome.of(fault, state);
    }

    Outcome cancel(String orderId) {
        var ledger = ledgers.current();
        var fault = faults.claim(FaultTarget.CANCEL, orderId);
        if (skipsLedger(fault)) {
            ledger.countCall(OrderCall.CANCEL, orderId, null);
            return Outcome.withoutLedger(fault);
        }

        fault.awaitDelay();
        return Outcome.of(fault, ledger.cancel(orderId));
    }

    Outcome find(String orderId) {
        var ledger = ledgers.current();
        var fault = faults.claim(FaultTarget.PAYMENT_LOOKUP, orderId);
        if (skipsLedger(fault)) {
            ledger.countCall(OrderCall.LOOKUP, orderId, null);
            return Outcome.withoutLedger(fault);
        }

        fault.awaitDelay();
        return new Outcome(fault, ledger.lookup(orderId));
    }

    // Note: PROCESSING, SERVER_ERROR, BAD_REQUEST는 원장을 건드리지 않는다. 처리 중이라는 응답이나 오류만 주고 상태는 그대로다
    private static boolean skipsLedger(Fault fault) {
        return fault.is(FaultMode.PROCESSING) || fault.is(FaultMode.SERVER_ERROR) || fault.is(FaultMode.BAD_REQUEST);
    }

    private OrderState.Decided decide(ApproveRequest request, Fault fault) {
        if (fault.is(FaultMode.DECLINE)) return new Declined(DECLINE_CODE);
        // 발급하지 않은 빌링키로 온 자동결제는 거절한다
        if (request.billingKey() != null && !billingKeys.isIssued(request.billingKey())) {
            return new Declined(BILLING_KEY_DECLINE_CODE);
        }
        // Note: 변조형은 저장하는 금액 자체를 바꾼다. 서비스의 INV-3 금액 대조가 이를 잡는지 계약 테스트가 확인한다
        // Note: 거래 ID에 서명하는 금액은 실제로 승인한 금액이다. 되살린 결제의 환불 한도가 이 금액이다
        var approvedAmount = approvedAmount(request.amount(), fault);
        return new Approved(request.orderId(), txIds.issue(approvedAmount, request.currency()), approvedAmount,
                request.currency(), Instant.now());
    }

    private static BigDecimal approvedAmount(BigDecimal requested, Fault fault) {
        if (fault.is(FaultMode.WRONG_AMOUNT)) return requested.subtract(WRONG_AMOUNT_DIFFERENCE);
        if (fault.is(FaultMode.FRACTIONAL_AMOUNT)) return requested.subtract(FRACTIONAL_AMOUNT_DIFFERENCE);
        return requested;
    }

    /**
     * 한 요청의 처리 결과. 컨트롤러는 적용한 장애({@code fault})의 응답을 먼저 쓰고, 그다음 원장 상태({@code state}) 응답을 쓴다.
     *
     * @param state 원장 상태. 원장을 건드리지 않았거나(PROCESSING·SERVER_ERROR) 조회한 주문이 없으면 비어 있다
     */
    record Outcome(Fault fault, Optional<OrderState> state) {
        static Outcome of(Fault fault, OrderState state) {
            return new Outcome(fault, Optional.of(state));
        }

        static Outcome withoutLedger(Fault fault) {
            return new Outcome(fault, Optional.empty());
        }

        boolean dropsResponse() {
            return fault.is(FaultMode.DROP_RESPONSE);
        }
    }
}
