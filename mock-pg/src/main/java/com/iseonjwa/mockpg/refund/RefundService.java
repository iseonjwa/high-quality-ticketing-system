package com.iseonjwa.mockpg.refund;

import com.iseonjwa.mockpg.fault.Fault;
import com.iseonjwa.mockpg.fault.FaultInjector;
import com.iseonjwa.mockpg.fault.FaultMode;
import com.iseonjwa.mockpg.fault.FaultTarget;
import com.iseonjwa.mockpg.ledger.LedgerHolder;
import com.iseonjwa.mockpg.ledger.RefundOutcome;
import com.iseonjwa.mockpg.ledger.RefundOutcome.Refunded;
import com.iseonjwa.mockpg.ledger.RefundOutcome.RejectCode;
import com.iseonjwa.mockpg.ledger.RefundOutcome.Rejected;
import com.iseonjwa.mockpg.ledger.TxIdSigner;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Mock PG의 환불과 환불 조회. 판정과 저장은 원장이 맡고(mock-pg.md 8절), 이 클래스는 후보를 만들어 넘기며
 * 요청마다 적용할 장애를 정해 5절 표대로 원장 처리 전후에 끼워 넣는다. 장애 설정은 형식 검증을 통과한 요청에서만 한 번 읽는다.
 * 호출 계수는 장애가 걸렸는지, 어떤 응답이 나가는지와 상관없이 센다.
 */
@Service
class RefundService {
    private final LedgerHolder ledgers;
    private final FaultInjector faults;
    private final TxIdSigner txIds;

    RefundService(LedgerHolder ledgers, FaultInjector faults, TxIdSigner txIds) {
        this.ledgers = ledgers;
        this.faults = faults;
        this.txIds = txIds;
    }

    RefundResult refund(RefundRequest request, String idempotencyKey) {
        // Note: 요청 하나는 처음 얻은 원장만 끝까지 쓴다. 지연 중 reset이 와도 계수가 새 원장에 섞이지 않는다
        var ledger = ledgers.current();
        var fault = faults.claim(FaultTarget.REFUND, request.refundId());
        // Note: 판정 결과와 상관없이 센다. 거절(422)과 장애 주입으로 원장에 닿지 않는 응답도 호출이다
        ledger.countRefundCall(request.refundId(), idempotencyKey);

        // PROCESSING·SERVER_ERROR는 원장을 건드리지 않는다. REJECT는 저장하지 않고 거절만 답한다
        if (fault.is(FaultMode.PROCESSING) || fault.is(FaultMode.SERVER_ERROR)) return new RefundResult(fault, null);
        if (fault.is(FaultMode.REJECT)) return new RefundResult(fault, new Rejected(RejectCode.NOT_REFUNDABLE));

        // Note: DELAY는 저장하기 전에 건다. 환불 워커가 응답 시간 초과를 받고 같은 refundId로 다시 호출하는 상황을 만들려는 것이다(8절)
        fault.awaitDelay();
        // Note: refundTxId와 환불 시각은 원장 잠금 밖에서 정한다. 같은 refundId가 이미 저장돼 있으면 이 후보는 버려지고 저장된 결과가 나간다
        var candidate = new Refunded(request.refundId(), "rf-" + UUID.randomUUID(), request.pgTxId(), request.amount(),
                request.currency(), Instant.now());
        // Note: 서명 확인은 순수 계산이라 잠금 밖에서 하고 결과만 원장에 넘긴다. 원장에 있는 결제면 쓰이지 않는다
        return new RefundResult(fault, ledger.refund(candidate, txIds.verify(request.pgTxId())));
    }

    LookupResult find(String refundId) {
        var ledger = ledgers.current();
        var fault = faults.claim(FaultTarget.REFUND_LOOKUP, refundId);
        ledger.countRefundLookup(refundId);
        if (fault.is(FaultMode.PROCESSING) || fault.is(FaultMode.SERVER_ERROR)) return new LookupResult(fault, Optional.empty());

        fault.awaitDelay();
        return new LookupResult(fault, ledger.findRefund(refundId));
    }

    /**
     * 환불 한 건의 처리 결과.
     *
     * @param outcome 판정 결과. 장애가 원장에 닿기 전에 응답을 정했으면(PROCESSING·SERVER_ERROR) null이다
     */
    record RefundResult(Fault fault, RefundOutcome outcome) {
        boolean dropsResponse() {
            return fault.is(FaultMode.DROP_RESPONSE);
        }
    }

    record LookupResult(Fault fault, Optional<Refunded> refunded) {
    }
}
