-- payment.result 소비가 재시도 끝에 실패해 DLQ로 간 메시지의 원문과 처리 결과(ISE-74, ADR 0022).
-- 결제 서비스 dead_letters와 같은 모양에서 환불 연결 컬럼만 뺐다. 한 번 쓰면 상태만 바꾸는 기록용 테이블이라 BaseEntity 감사 컬럼은 두지 않는다

CREATE TABLE dead_letters (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_topic VARCHAR(100),
    source_partition INT,
    source_offset BIGINT,
    message_key VARCHAR(100),
    -- 멱등 키. eventId를 읽었으면 그 값, 못 읽었으면 원본 topic-partition-offset
    event_key VARCHAR(200) NOT NULL,
    event_id UUID,
    payload_raw TEXT NOT NULL,
    reason VARCHAR(500),
    status VARCHAR(10) NOT NULL,
    resolution VARCHAR(20),
    created_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    CONSTRAINT uq_dead_letters_event_key UNIQUE (event_key),
    CONSTRAINT chk_dead_letters_status CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT chk_dead_letters_resolution CHECK (resolution IN ('REPLAYED')),
    CONSTRAINT chk_dead_letters_resolved CHECK (status <> 'RESOLVED' OR resolution IS NOT NULL)
);

-- 사람이 볼 OPEN 건을 오래된 순으로 찾는다
CREATE INDEX idx_dead_letters_open_created_at ON dead_letters (status, created_at)
    WHERE status = 'OPEN';
