-- 결제 핵심 테이블. 설계 기준: docs/design/payment/data-model.md 2~7절
-- refunds·dead_letters·manual_action_logs·reconciliation_mismatches는 V2(M3), billing_keys는 V3(M4)에서 만든다.

CREATE TABLE payments (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_no VARCHAR(20) NOT NULL,
    reservation_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    amount NUMERIC(15, 0) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'KRW',
    method VARCHAR(10) NOT NULL,
    status VARCHAR(20) NOT NULL,
    pg_order_id UUID NOT NULL,
    pg_tx_id VARCHAR(200),
    pg_approved_amount NUMERIC(15, 0),
    decline_code VARCHAR(30),
    unknown_retry_count SMALLINT NOT NULL DEFAULT 0,
    next_check_at TIMESTAMPTZ,
    approved_at TIMESTAMPTZ,
    refunded_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by BIGINT,
    deleted_at TIMESTAMPTZ,
    deleted_by BIGINT,
    CONSTRAINT uq_payments_payment_no UNIQUE (payment_no),
    -- 예매당 결제 하나(INV-1, ADR 0026). 상태와 무관한 전체 UNIQUE라 거절·환불된 예매도 두 번째 행을 만들 수 없다
    CONSTRAINT uq_payments_reservation_id UNIQUE (reservation_id),
    CONSTRAINT uq_payments_pg_order_id UNIQUE (pg_order_id),
    -- 한 PG 거래가 두 결제에 연결되지 않게 한다. NULL은 서로 다른 값이라 미승인 결제는 여럿이어도 된다
    CONSTRAINT uq_payments_pg_tx_id UNIQUE (pg_tx_id),
    CONSTRAINT chk_payments_amount CHECK (amount > 0),
    CONSTRAINT chk_payments_currency CHECK (currency = 'KRW'),
    CONSTRAINT chk_payments_method CHECK (method IN ('CARD', 'BILLING')),
    CONSTRAINT chk_payments_status CHECK (status IN
        ('REQUESTED', 'APPROVED', 'DECLINED', 'UNKNOWN', 'REFUNDED', 'NEEDS_MANUAL_CHECK')),
    -- 재확인 스케줄러가 집지 못하는 미확정 결제를 막는다(INV-4·INV-8)
    CONSTRAINT chk_payments_check_schedule CHECK (
        status NOT IN ('REQUESTED', 'UNKNOWN') OR next_check_at IS NOT NULL),
    -- PG 거래 식별자 없는 승인, 금액이 다른 승인을 막는다(INV-3)
    CONSTRAINT chk_payments_approved CHECK (
        status <> 'APPROVED'
        OR (pg_tx_id IS NOT NULL AND approved_at IS NOT NULL AND pg_approved_amount = amount)),
    CONSTRAINT chk_payments_refunded CHECK (
        status <> 'REFUNDED' OR (pg_tx_id IS NOT NULL AND refunded_at IS NOT NULL)),
    CONSTRAINT chk_payments_declined CHECK (status <> 'DECLINED' OR decline_code IS NOT NULL)
);

-- 재확인 스케줄러가 다음 조회 대상을 찾는 인덱스
CREATE INDEX idx_payments_next_check_at ON payments (next_check_at)
    WHERE status IN ('REQUESTED', 'UNKNOWN');
-- 확정되지 못한 결제의 최고령 Gauge(ADR 0024)
CREATE INDEX idx_payments_unsettled_created_at ON payments (status, created_at)
    WHERE status IN ('REQUESTED', 'UNKNOWN', 'NEEDS_MANUAL_CHECK');

-- 상태 이력. 기록용 테이블이라 감사 컬럼 대신 created_at만 둔다.
-- append-only 트리거는 Ops 이슈(M3)에서 reject_modification()과 함께 추가한다.
CREATE TABLE payment_status_histories (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id BIGINT NOT NULL REFERENCES payments (id),
    target_type VARCHAR(10) NOT NULL,
    from_status VARCHAR(20),
    to_status VARCHAR(20) NOT NULL,
    reason VARCHAR(50),
    actor VARCHAR(40) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_payment_status_histories_target_type CHECK (target_type IN ('PAYMENT', 'REFUND'))
);

CREATE INDEX idx_payment_status_histories_payment_id ON payment_status_histories (payment_id, id);

CREATE TABLE idempotency_records (
    user_id BIGINT NOT NULL,
    idem_key UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    resource_id BIGINT NOT NULL,
    link_type VARCHAR(10) NOT NULL,
    response_status SMALLINT,
    response_body JSONB,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, idem_key),
    -- ORIGIN은 TX1에서 결제를 만든 키, ALIAS는 기존 결제에 연결된 새 키(ADR 0019)
    CONSTRAINT chk_idempotency_records_link_type CHECK (link_type IN ('ORIGIN', 'ALIAS'))
);

CREATE INDEX idx_idempotency_records_resource_id ON idempotency_records (resource_id);

CREATE TABLE outbox_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id UUID NOT NULL,
    topic VARCHAR(100) NOT NULL,
    message_key VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(10) NOT NULL,
    attempts SMALLINT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    claimed_until TIMESTAMPTZ,
    last_error VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ,
    CONSTRAINT uq_outbox_events_event_id UNIQUE (event_id),
    CONSTRAINT chk_outbox_events_status CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);

CREATE INDEX idx_outbox_events_pending_next_attempt ON outbox_events (next_attempt_at, id) WHERE status = 'PENDING';
CREATE INDEX idx_outbox_events_pending_message_key ON outbox_events (message_key, id) WHERE status = 'PENDING';
CREATE INDEX idx_outbox_events_sent_at ON outbox_events (sent_at) WHERE status = 'SENT';
CREATE INDEX idx_outbox_events_created_at ON outbox_events (created_at);

CREATE TABLE processed_events (
    event_id UUID NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (event_id, consumer_group)
);

CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);
