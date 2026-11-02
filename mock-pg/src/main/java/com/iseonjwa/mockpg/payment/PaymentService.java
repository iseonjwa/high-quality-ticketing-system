package com.iseonjwa.mockpg.payment;

import com.iseonjwa.mockpg.fault.FaultInjector;
import com.iseonjwa.mockpg.fault.Fault;
import com.iseonjwa.mockpg.fault.FaultMode;
import com.iseonjwa.mockpg.fault.FaultTarget;
import com.iseonjwa.mockpg.ledger.LedgerHolder;
import com.iseonjwa.mockpg.ledger.OrderState;
import com.iseonjwa.mockpg.ledger.OrderState.Approved;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Mock PG의 승인·망취소·조회. 상태 전이는 원장이 맡고, 이 클래스는 요청마다 적용할 장애와 승인 결과를 정한다.
 */
@Service
class PaymentService {
    private final LedgerHolder ledgers;
    private final FaultInjector faults;

    PaymentService(LedgerHolder ledgers, FaultInjector faults) {
        this.ledgers = ledgers;
        this.faults = faults;
    }

    /**
     * 승인한다. 장애 설정은 형식 검증을 통과한 요청에서만 한 번 읽는다. 잘못된 요청이 장애 횟수를 쓰지 않게 하려는 것이다.
     */
    Approval approve(ApproveRequest request) {
        var fault = faults.claim(FaultTarget.APPROVE, request.orderId());
        var state = ledgers.current().approve(request.orderId(), () -> approved(request));
        return new Approval(state, fault);
    }

    OrderState cancel(String orderId) {
        return ledgers.current().cancel(orderId);
    }

    Optional<OrderState> find(String orderId) {
        return ledgers.current().find(orderId);
    }

    private static Approved approved(ApproveRequest request) {
        return new Approved(request.orderId(), "pg-" + UUID.randomUUID(), request.amount(), request.currency(),
                Instant.now());
    }

    record Approval(OrderState state, Fault fault) {
        boolean dropsResponse() {
            return fault.is(FaultMode.DROP_RESPONSE);
        }
    }
}
