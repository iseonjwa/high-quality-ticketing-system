-- 결제·예매 대사가 찾은 불일치. 설계 기준: docs/design/payment/data-model.md 10절, ADR 0029
-- 같은 불일치는 OPEN 행 하나를 이어 갱신하고, 다음 대사에서 맞으면 RESOLVED로 닫는다. 결제·예매 데이터는 고치지 않는다.

CREATE TABLE reconciliation_mismatches (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rule VARCHAR(10) NOT NULL,
    reservation_id BIGINT,
    payment_id BIGINT,
    detail JSONB NOT NULL,
    status VARCHAR(10) NOT NULL,
    first_detected_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    CONSTRAINT chk_reconciliation_mismatches_rule CHECK (rule IN
        ('RC-1', 'RC-2', 'RC-3', 'RC-4', 'RC-5', 'RC-6', 'RC-7', 'RC-8')),
    CONSTRAINT chk_reconciliation_mismatches_status CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT chk_reconciliation_mismatches_resolved CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL))
);

-- 같은 불일치는 행 하나로 이어 본다. NULL 대상은 0으로 바꿔 비교한다(INSERT ... ON CONFLICT의 충돌 대상이다)
CREATE UNIQUE INDEX uq_reconciliation_mismatches_open
    ON reconciliation_mismatches (rule, COALESCE(reservation_id, 0), COALESCE(payment_id, 0))
    WHERE status = 'OPEN';

-- 규칙별 OPEN 건수 Gauge
CREATE INDEX idx_reconciliation_mismatches_open ON reconciliation_mismatches (status, rule)
    WHERE status = 'OPEN';

-- inspect가 결제·예매별로 찾는 인덱스
CREATE INDEX idx_reconciliation_mismatches_payment ON reconciliation_mismatches (payment_id, id);
CREATE INDEX idx_reconciliation_mismatches_reservation ON reconciliation_mismatches (reservation_id, id);
