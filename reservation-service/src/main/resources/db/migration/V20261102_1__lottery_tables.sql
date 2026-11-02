-- 추첨 테이블. 설계 기준: SA MASTER 12-3(reservation DB), docs/design/lottery/details.md 1절
-- 추첨석은 schedule_seats.grade = 'LOTTERY'로 떼어 두므로(D9) 좌석 테이블은 바꾸지 않는다.

-- 회차당 추첨 1개(D2, D9). 상태는 조건부 UPDATE로만 전이해 마감·추첨 재실행을 막는다
CREATE TABLE lotteries (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    schedule_id BIGINT NOT NULL REFERENCES schedules (id),
    -- 추첨석을 떼어 온 등급. 종결 때 남은 추첨석을 이 등급으로 되돌린다
    source_grade VARCHAR(10) NOT NULL,
    seat_count INT NOT NULL,
    waitlist_count INT NOT NULL,
    max_per_entry SMALLINT NOT NULL,
    entry_start_at TIMESTAMPTZ NOT NULL,
    entry_end_at TIMESTAMPTZ NOT NULL,
    draw_at TIMESTAMPTZ NOT NULL,
    -- 순위 계산 SHA-256(seed ‖ entryId)의 시드(D7). 설정 때 SecureRandom 32바이트로 채우고 바꾸지 않는다(ADR 0041)
    seed BYTEA NOT NULL,
    status VARCHAR(10) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by BIGINT,
    deleted_at TIMESTAMPTZ,
    deleted_by BIGINT,
    CONSTRAINT uq_lotteries_schedule_id UNIQUE (schedule_id),
    CONSTRAINT chk_lotteries_source_grade CHECK (source_grade <> 'LOTTERY'),
    CONSTRAINT chk_lotteries_seat_count CHECK (seat_count > 0),
    CONSTRAINT chk_lotteries_waitlist_count CHECK (waitlist_count >= 0),
    CONSTRAINT chk_lotteries_max_per_entry CHECK (max_per_entry BETWEEN 1 AND 2),
    CONSTRAINT chk_lotteries_period CHECK (entry_start_at < entry_end_at AND entry_end_at <= draw_at),
    CONSTRAINT chk_lotteries_seed CHECK (octet_length(seed) = 32),
    CONSTRAINT chk_lotteries_status CHECK (status IN ('OPEN', 'CLOSED', 'DRAWN', 'DONE'))
);

-- 응모 한 건. request_id UNIQUE가 응모 멱등을 맡는다(idempotency_records를 쓰지 않는다)
CREATE TABLE lottery_entries (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    lottery_id BIGINT NOT NULL REFERENCES lotteries (id),
    request_id UUID NOT NULL,
    user_id BIGINT NOT NULL,
    quantity SMALLINT NOT NULL,
    status VARCHAR(12) NOT NULL,
    -- 추첨 순위. 당첨·예비자의 승계 순서다
    draw_rank INT,
    reservation_id BIGINT REFERENCES reservations (id),
    -- 우리 책임 실패(NOT_SENT) 재시도 횟수(D12)
    payment_attempts SMALLINT NOT NULL DEFAULT 0,
    fail_reason VARCHAR(30),
    created_at TIMESTAMPTZ NOT NULL,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by BIGINT,
    deleted_at TIMESTAMPTZ,
    deleted_by BIGINT,
    CONSTRAINT uq_lottery_entries_request_id UNIQUE (request_id),
    -- 1인 1회차 1응모. 취소한 행도 남겨 같은 회차 재응모를 막는다(D2, D5)
    CONSTRAINT uq_lottery_entries_lottery_user UNIQUE (lottery_id, user_id),
    CONSTRAINT chk_lottery_entries_quantity CHECK (quantity BETWEEN 1 AND 2),
    CONSTRAINT chk_lottery_entries_payment_attempts CHECK (payment_attempts >= 0),
    CONSTRAINT chk_lottery_entries_status
        CHECK (status IN ('ENTERED', 'CANCELED', 'WON', 'WAITLISTED', 'LOST', 'PAID', 'FAILED'))
);

-- 승계 워커가 다음 예비자를 순위대로 찾는 인덱스(D13)
CREATE INDEX idx_lottery_entries_lottery_status_draw_rank ON lottery_entries (lottery_id, status, draw_rank);

-- 배정 워커가 아직 예매를 만들지 않은 당첨자만 찾는 부분 인덱스(D10)
CREATE INDEX idx_lottery_entries_won_unassigned
    ON lottery_entries (lottery_id)
    WHERE status = 'WON' AND reservation_id IS NULL;
