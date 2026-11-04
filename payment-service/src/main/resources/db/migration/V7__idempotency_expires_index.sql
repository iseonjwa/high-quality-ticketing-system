-- 만료된 멱등 기록을 정리 작업이 오래된 순서로 읽을 때 쓰는 인덱스(ADR 0022 결정 5·6).
-- PK (user_id, idem_key)와 resource_id 인덱스로는 expires_at 범위를 찾지 못해 기록이 쌓일수록 정리가 전체를 훑는다.
CREATE INDEX idx_idempotency_records_expires_at ON idempotency_records (expires_at);
