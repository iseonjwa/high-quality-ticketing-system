package com.iseonjwa.mockpg.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 원장에 저장되는 주문 하나의 상태(mock-pg.md 4절). 주문이 없으면 원장에 항목이 없다.
 *
 * <pre>
 * 없음 --승인--> IN_FLIGHT --(내 시도일 때만)--> APPROVED | DECLINED
 * 없음 --망취소--> CANCELED
 * </pre>
 */
public sealed interface OrderState permits OrderState.InFlight, OrderState.Decided, OrderState.Canceled {

    /**
     * 승인을 처리하는 중이다. 시도마다 새 객체이고 같은 시도인지는 객체 동일성으로 판단한다. 확정과 되돌리기는 자기 시도일 때만 한다.
     *
     * <p>Note: 필드와 equals/hashCode가 없다. 시도마다 UUID(SecureRandom)를 만들지 않으려는 것이다.
     */
    final class InFlight implements OrderState {
        private InFlight() {
        }

        static InFlight newAttempt() {
            return new InFlight();
        }
    }

    /**
     * 승인 시도가 확정한 결과. 같은 주문의 재요청에는 이 결과를 그대로 돌려준다(멱등).
     */
    sealed interface Decided extends OrderState permits Approved, Declined {
    }

    record Approved(String orderId, String pgTxId, BigDecimal amount, String currency, Instant approvedAt)
            implements Decided {
        public Approved {
            Objects.requireNonNull(orderId);
            Objects.requireNonNull(pgTxId);
            Objects.requireNonNull(amount);
            Objects.requireNonNull(currency);
            Objects.requireNonNull(approvedAt);
        }
    }

    record Declined(String declineCode) implements Decided {
        public Declined {
            Objects.requireNonNull(declineCode);
        }
    }

    /**
     * 망취소됐다. 이후 같은 주문은 승인되지 않는다(INV-8).
     */
    record Canceled() implements OrderState {
        static final Canceled INSTANCE = new Canceled();
    }
}
