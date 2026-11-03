package com.iseonjwa.mockpg.ledger;

import com.iseonjwa.mockpg.fault.FaultInjector;
import com.iseonjwa.mockpg.ledger.LedgerViews.OrderPage;
import com.iseonjwa.mockpg.ledger.LedgerViews.OrderView;
import com.iseonjwa.mockpg.ledger.LedgerViews.PaymentRefundsView;
import com.iseonjwa.mockpg.ledger.LedgerViews.Summary;
import com.iseonjwa.mockpg.web.ErrorResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 원장 조회와 초기화 API(mock-pg.md 9절). 장애 주입 API와 같은 방식으로 {@code test}·{@code loadtest}에서만 연다.
 * local·prod에서는 빈이 없어 404다. 조회는 호출 계수를 올리지 않는다.
 */
@Profile("(test | loadtest) & !prod")
@RestController
@RequestMapping("/mock-pg")
class LedgerController {
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;
    private static final Set<String> ORDER_STATUSES = Set.of("IN_FLIGHT", "APPROVED", "DECLINED", "CANCELED");

    private final LedgerHolder ledgers;
    private final FaultInjector faults;

    LedgerController(LedgerHolder ledgers, FaultInjector faults) {
        this.ledgers = ledgers;
        this.faults = faults;
    }

    @GetMapping("/ledger/payments/{orderId}")
    ResponseEntity<?> payment(@PathVariable String orderId) {
        return ledgers.current().orderView(orderId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(ErrorResponse.NOT_FOUND));
    }

    @GetMapping("/ledger/refunds")
    ResponseEntity<?> refunds(@RequestParam(required = false) String pgTxId) {
        if (pgTxId == null || pgTxId.isBlank()) return ResponseEntity.badRequest().body(ErrorResponse.INVALID_REQUEST);
        return ResponseEntity.ok(ledgers.current().refundsOf(pgTxId));
    }

    @GetMapping("/ledger/summary")
    Summary summary() {
        return ledgers.current().summary();
    }

    @GetMapping("/ledger/orders")
    ResponseEntity<?> orders(@RequestParam(required = false) String status,
                             @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit,
                             @RequestParam(defaultValue = "0") long cursor) {
        if (status != null && !ORDER_STATUSES.contains(status)) return invalid();
        if (limit < 1 || limit > MAX_LIMIT || cursor < 0) return invalid();
        OrderPage page = ledgers.current().orders(status, limit, cursor);
        return ResponseEntity.ok(page);
    }

    /** 원장과 장애 설정을 새 객체로 바꾼다. 테스트 사이 격리용이다. */
    @PostMapping("/reset")
    ResponseEntity<Void> reset() {
        ledgers.reset();
        faults.reset();
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<?> invalid() {
        return ResponseEntity.badRequest().body(ErrorResponse.INVALID_REQUEST);
    }
}
