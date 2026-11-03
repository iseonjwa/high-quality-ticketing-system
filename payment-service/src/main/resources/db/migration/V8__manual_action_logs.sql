-- Ops 명령 감사 기록과 이력 테이블의 append-only 보호. 설계 기준: docs/design/payment/data-model.md 3·9절, ADR 0028
-- 직접 SQL로 이력·감사를 고치지 못하게 DB가 거부한다(INV-7). 트리거는 테이블 소유 계정이 끌 수 있다(계정 분리는 후속 과제).

-- 한 번 쓰면 바꾸지 않는 테이블이 함께 쓰는 거부 함수. 테이블 이름을 메시지에 넣어 어느 테이블인지 알 수 있게 한다
CREATE FUNCTION reject_modification() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END $$;

CREATE TABLE manual_action_logs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    command VARCHAR(40) NOT NULL,
    target_type VARCHAR(20) NOT NULL,
    target_id BIGINT NOT NULL,
    from_status VARCHAR(20),
    to_status VARCHAR(20),
    result VARCHAR(10) NOT NULL,
    plan_hash CHAR(64) NOT NULL,
    pg_evidence JSONB,
    external_ref VARCHAR(200),
    operator VARCHAR(40) NOT NULL,
    approver VARCHAR(40),
    reason VARCHAR(500) NOT NULL,
    performed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_manual_action_logs_target_type CHECK (target_type IN ('PAYMENT', 'REFUND', 'OUTBOX', 'DEAD_LETTER')),
    CONSTRAINT chk_manual_action_logs_result CHECK (result IN ('APPLIED', 'REJECTED')),
    -- 소문자 16진수 64자(SHA-256). 계획을 만들 수 없는 거부는 64자 0을 쓴다
    CONSTRAINT chk_manual_action_logs_plan_hash CHECK (plan_hash ~ '^[0-9a-f]{64}$'),
    -- 4-eyes: 승인자가 있으면 실행자와 달라야 한다
    CONSTRAINT chk_manual_action_logs_approver CHECK (approver IS NULL OR approver <> operator)
);

-- inspect가 대상별 기록을 읽는다
CREATE INDEX idx_manual_action_logs_target ON manual_action_logs (target_type, target_id, id);

CREATE TRIGGER manual_action_logs_no_update BEFORE UPDATE OR DELETE ON manual_action_logs
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER manual_action_logs_no_truncate BEFORE TRUNCATE ON manual_action_logs
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

CREATE TRIGGER payment_status_histories_no_update BEFORE UPDATE OR DELETE ON payment_status_histories
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER payment_status_histories_no_truncate BEFORE TRUNCATE ON payment_status_histories
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();
