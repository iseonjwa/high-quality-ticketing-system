-- 공통 이벤트 신뢰성 테이블(SA 12-5, ISE-147). 공통 모듈(common messaging)이 테이블 이름과 컬럼을 고정해서 쓰므로
-- payment-service의 V1·V2와 같은 구조로 만든다. 예매 흐름의 RefundRequested(ISE-73)와 추첨의 BillingPaymentRequested(ISE-92)가
-- 함께 쓴다. 기록용 테이블이라 감사 컬럼은 created_at만 둔다(SA 12-1).

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

-- 릴레이가 PENDING 행을 id 순으로 집을 때 쓴다. SENT가 쌓여도 훑지 않게 PENDING만 담는다
CREATE INDEX idx_outbox_events_pending_id ON outbox_events (id) WHERE status = 'PENDING';
CREATE INDEX idx_outbox_events_pending_next_attempt ON outbox_events (next_attempt_at, id) WHERE status = 'PENDING';
-- 같은 키의 앞선 미발행 행이 있으면 기다려 키 순서를 지킨다
CREATE INDEX idx_outbox_events_pending_message_key ON outbox_events (message_key, id) WHERE status = 'PENDING';
CREATE INDEX idx_outbox_events_sent_at ON outbox_events (sent_at) WHERE status = 'SENT';
CREATE INDEX idx_outbox_events_created_at ON outbox_events (created_at);

-- 소비자 중복 처리 방지(같은 이벤트를 소비자 그룹마다 한 번만 처리)
CREATE TABLE processed_events (
    event_id UUID NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (event_id, consumer_group)
);

CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);
