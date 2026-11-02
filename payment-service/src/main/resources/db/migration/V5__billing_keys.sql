-- 빌링키(SA 12-4, ISE-96). 추첨 당첨 자동결제에 쓴다. 원문은 저장하지 않고 AES-256-GCM 암호문만 둔다(ADR 0042).
CREATE TABLE billing_keys (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    billing_key_enc VARCHAR(512) NOT NULL,
    card_last4 CHAR(4),
    status VARCHAR(10) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by BIGINT,
    deleted_at TIMESTAMPTZ,
    deleted_by BIGINT,
    CONSTRAINT chk_billing_keys_status CHECK (status IN ('ACTIVE', 'REVOKED'))
);

-- 사용자당 활성 빌링키는 하나다. 다시 등록하면 기존 것을 REVOKED로 바꾼 뒤 새로 넣는다
CREATE UNIQUE INDEX uq_billing_keys_user_active ON billing_keys (user_id) WHERE status = 'ACTIVE';
