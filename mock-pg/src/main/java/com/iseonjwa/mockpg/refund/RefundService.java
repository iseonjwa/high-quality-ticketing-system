package com.iseonjwa.mockpg.refund;

import com.iseonjwa.mockpg.ledger.LedgerHolder;
import com.iseonjwa.mockpg.ledger.RefundOutcome;
import com.iseonjwa.mockpg.ledger.RefundOutcome.Refunded;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Mock PG의 환불과 환불 조회. 판정과 저장은 원장이 맡고(mock-pg.md 8절), 이 클래스는 후보를 만들어 넘긴다.
 * 환불 장애 주입(ISE-133)과 호출 계수(ISE-128)는 아직 없다.
 */
@Service
class RefundService {
    private final LedgerHolder ledgers;

    RefundService(LedgerHolder ledgers) {
        this.ledgers = ledgers;
    }

    RefundOutcome refund(RefundRequest request) {
        // Note: refundTxId와 환불 시각은 원장 잠금 밖에서 정한다. 같은 refundId가 이미 저장돼 있으면 이 후보는 버려지고 저장된 결과가 나간다
        var candidate = new Refunded(request.refundId(), "rf-" + UUID.randomUUID(), request.pgTxId(), request.amount(),
                request.currency(), Instant.now());
        return ledgers.current().refund(candidate);
    }

    Optional<Refunded> find(String refundId) {
        return ledgers.current().findRefund(refundId);
    }
}
