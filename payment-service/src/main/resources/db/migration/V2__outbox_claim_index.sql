-- 릴레이가 PENDING 행을 id 순으로 집을 때 쓰는 인덱스. SENT가 쌓여도(7일 보관) 훑지 않게 PENDING만 담는다.
-- V1의 (next_attempt_at, id) 인덱스는 id 정렬을 받쳐 주지 못해 SENT 수에 비례해 느려졌다.
CREATE INDEX idx_outbox_pending_id ON outbox (id) WHERE status = 'PENDING';
