-- 릴레이가 같은 키의 앞선 FAILED 행을 찾을 때 쓰는 인덱스. FAILED는 드물어 부분 인덱스가 아주 작다.
-- V1의 (message_key, id) WHERE status = 'PENDING' 인덱스와 짝이다. 조건을 status <> 'SENT'로 합치면 그 인덱스를 못 쓴다.
CREATE INDEX idx_outbox_events_failed_message_key ON outbox_events (message_key, id) WHERE status = 'FAILED';
