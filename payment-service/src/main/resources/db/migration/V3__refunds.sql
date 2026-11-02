-- 환불 테이블. 설계 기준: docs/design/payment/data-model.md 4절
-- dead_letters·manual_action_logs·reconciliation_mismatches는 이후 M3 이슈에서 만든다.
-- 환불 상태 이력은 새 테이블 없이 payment_status_histories에 target_type = 'REFUND'로 쓴다.

CREATE TABLE refunds (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id BIGINT NOT NULL REFERENCES payments (id),
    pg_refund_id UUID NOT NULL,
    amount NUMERIC(15, 0) NOT NULL,
    reason VARCHAR(30) NOT NULL,
    source VARCHAR(10) NOT NULL,
    source_event_id UUID,
    status VARCHAR(20) NOT NULL,
    attempt SMALLINT NOT NULL DEFAULT 0,
    next_check_at TIMESTAMPTZ,
    pg_requested_at TIMESTAMPTZ,
    pg_refund_ref VARCHAR(200),
    refunded_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by BIGINT,
    deleted_at TIMESTAMPTZ,
    deleted_by BIGINT,
    -- 결제당 환불 하나, 전액 환불(INV-2). INSERT ... ON CONFLICT (payment_id)의 충돌 대상이다
    CONSTRAINT uq_refunds_payment_id UNIQUE (payment_id),
    CONSTRAINT uq_refunds_pg_refund_id UNIQUE (pg_refund_id),
    CONSTRAINT chk_refunds_amount CHECK (amount > 0),
    CONSTRAINT chk_refunds_reason CHECK (reason IN
        ('EXPIRED_AFTER_APPROVAL', 'CANCELED', 'DUPLICATE_APPROVAL', 'NOT_CONFIRMABLE', 'PG_AMOUNT_MISMATCH')),
    CONSTRAINT chk_refunds_source CHECK (source IN ('EVENT', 'DLQ', 'OPS')),
    CONSTRAINT chk_refunds_status CHECK (status IN
        ('REQUESTED', 'UNKNOWN', 'REFUNDED', 'NEEDS_MANUAL_CHECK', 'FAILED')),
    -- 환불 워커가 집지 못하는 미확정 환불을 막는다
    CONSTRAINT chk_refunds_check_schedule CHECK (
        status NOT IN ('REQUESTED', 'UNKNOWN') OR next_check_at IS NOT NULL),
    CONSTRAINT chk_refunds_refunded CHECK (status <> 'REFUNDED' OR refunded_at IS NOT NULL)
);

-- 환불 워커가 다음 대상을 찾는 인덱스
CREATE INDEX idx_refunds_next_check_at ON refunds (next_check_at)
    WHERE status IN ('REQUESTED', 'UNKNOWN');
-- 확정되지 못한 환불의 최고령 Gauge
CREATE INDEX idx_refunds_unsettled_created_at ON refunds (status, created_at)
    WHERE status IN ('REQUESTED', 'UNKNOWN', 'NEEDS_MANUAL_CHECK');
