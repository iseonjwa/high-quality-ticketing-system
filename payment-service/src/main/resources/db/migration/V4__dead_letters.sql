-- DLQ로 온 메시지의 원문과 처리 결과. 설계 기준: docs/design/payment/data-model.md 8절
-- manual_action_logs·reconciliation_mismatches는 이후 M3 이슈에서 만든다.

CREATE TABLE dead_letters (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_topic VARCHAR(100),
    source_partition INT,
    source_offset BIGINT,
    message_key VARCHAR(100),
    -- 멱등 키. eventId가 있으면 그 값, 없으면 원본 topic-partition-offset
    event_key VARCHAR(200) NOT NULL,
    event_id UUID,
    event_type VARCHAR(100),
    payload_raw TEXT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(10) NOT NULL,
    resolution VARCHAR(20),
    refund_id BIGINT REFERENCES refunds (id),
    created_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    CONSTRAINT uq_dead_letters_event_key UNIQUE (event_key),
    CONSTRAINT chk_dead_letters_status CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT chk_dead_letters_resolution CHECK (resolution IN
        ('AUTO_PERSISTED', 'DUPLICATE', 'REPLAYED', 'NO_OBLIGATION')),
    -- 닫힌 건은 어떻게 닫혔는지 남긴다
    CONSTRAINT chk_dead_letters_resolved CHECK (status <> 'RESOLVED' OR resolution IS NOT NULL)
);

-- OPEN 건수와 최고령 Gauge
CREATE INDEX idx_dead_letters_open_created_at ON dead_letters (status, created_at)
    WHERE status = 'OPEN';
