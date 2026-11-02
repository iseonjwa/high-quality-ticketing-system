package com.iseonjwa.mockpg.payment;

import com.iseonjwa.mockpg.fault.Fault;
import com.iseonjwa.mockpg.fault.FaultInjector;
import com.iseonjwa.mockpg.fault.FaultMode;
import com.iseonjwa.mockpg.fault.FaultTarget;
import com.iseonjwa.mockpg.ledger.LedgerHolder;
import com.iseonjwa.mockpg.ledger.OrderState;
import com.iseonjwa.mockpg.ledger.OrderState.Approved;
import com.iseonjwa.mockpg.ledger.OrderState.Declined;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mock PG의 승인·망취소·조회. 상태 전이는 원장이 맡고, 이 클래스는 요청마다 적용할 장애를 정해 mock-pg.md 5절 표대로
 * 원장 처리 전후에 끼워 넣는다. 장애 설정은 형식 검증을 통과한 요청에서만 한 번 읽는다. 잘못된 요청이 장애 횟수를 쓰지 않게 하려는 것이다.
 */
@Service
class PaymentService {
    private static final String DECLINE_CODE = "CARD_DECLINED";

    private final LedgerHolder ledgers;
    private final FaultInjector faults;

    PaymentService(LedgerHolder ledgers, FaultInjector faults) {
        this.ledgers = ledgers;
        this.faults = faults;
    }

    Outcome approve(ApproveRequest request) {
        var fault = faults.claim(FaultTarget.APPROVE, request.orderId());
        if (skipsLedger(fault)) return Outcome.withoutLedger(fault);

        // Note: 새 주문이면 결정 안에서 기다려 지연 동안 원장이 IN_FLIGHT로 남는다.
        // 이미 상태가 있는 주문은 결정을 부르지 않으므로 응답 직전에 기다린다
        var decisionRan = new AtomicBoolean();
        var state = ledgers.current().approve(request.orderId(), () -> {
            decisionRan.set(true);
            delay(fault);
            return decide(request, fault);
        });
        if (!decisionRan.get()) delay(fault);
        return Outcome.of(fault, state);
    }

    Outcome cancel(String orderId) {
        var fault = faults.claim(FaultTarget.CANCEL, orderId);
        if (skipsLedger(fault)) return Outcome.withoutLedger(fault);

        delay(fault);
        return Outcome.of(fault, ledgers.current().cancel(orderId));
    }

    Outcome find(String orderId) {
        var fault = faults.claim(FaultTarget.PAYMENT_LOOKUP, orderId);
        if (skipsLedger(fault)) return Outcome.withoutLedger(fault);

        delay(fault);
        return new Outcome(fault, ledgers.current().find(orderId));
    }

    // Note: PROCESSING과 SERVER_ERROR는 원장을 건드리지 않는다. 처리 중이라는 응답이나 오류만 주고 상태는 그대로다
    private static boolean skipsLedger(Fault fault) {
        return fault.is(FaultMode.PROCESSING) || fault.is(FaultMode.SERVER_ERROR);
    }

    private static OrderState.Decided decide(ApproveRequest request, Fault fault) {
        if (fault.is(FaultMode.DECLINE)) return new Declined(DECLINE_CODE);
        return new Approved(request.orderId(), "pg-" + UUID.randomUUID(), request.amount(), request.currency(),
                Instant.now());
    }

    private static void delay(Fault fault) {
        if (!fault.is(FaultMode.DELAY)) return;
        try {
            Thread.sleep(fault.delayMs());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("장애 주입 지연 중 인터럽트", exception);
        }
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
