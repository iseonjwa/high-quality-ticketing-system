-- 좌석 선점·예매 테이블. 설계 기준: SA MASTER 12-1(공통 규칙), 12-3(reservation DB)
-- 예매·선점 요청·보유 매수는 BaseEntity 감사 컬럼을 두고,
-- reservation_status_histories는 한 번 쓰면 바꾸지 않는 기록용 테이블이라 created_at만 둔다(SA 12-1).

-- 사용자·회차별 보유 매수(D1). 같은 사용자의 동시 선점 요청을 이 행의 행 락에서 줄 세운다(SA 12-7)
CREATE TABLE user_schedule_quotas (
    user_id BIGINT NOT NULL,
    schedule_id BIGINT NOT NULL REFERENCES schedules (id),
    held_count SMALLINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by BIGINT,
    deleted_at TIMESTAMPTZ,
    deleted_by BIGINT,
    PRIMARY KEY (user_id, schedule_id),
    CONSTRAINT chk_user_schedule_quotas_held_count CHECK (held_count >= 0)
);

-- 좌석 일괄 선점 한 건. 상태와 만료 시각은 조건부 UPDATE로만 전이한다(SA 8-4)
CREATE TABLE reservations (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reservation_no VARCHAR(20) NOT NULL,
    request_id UUID NOT NULL,
    user_id BIGINT NOT NULL,
    schedule_id BIGINT NOT NULL REFERENCES schedules (id),
    status VARCHAR(20) NOT NULL,
    quantity SMALLINT NOT NULL,
    total_amount NUMERIC(15, 0) NOT NULL,
    hold_expires_at TIMESTAMPTZ,
    payment_id BIGINT,
    source VARCHAR(10) NOT NULL DEFAULT 'GENERAL',
    created_at TIMESTAMPTZ NOT NULL,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by BIGINT,
    deleted_at TIMESTAMPTZ,
    deleted_by BIGINT,
    CONSTRAINT uq_reservations_reservation_no UNIQUE (reservation_no),
    CONSTRAINT uq_reservations_request_id UNIQUE (request_id),
    CONSTRAINT chk_reservations_status CHECK (status IN ('HELD', 'CONFIRMED', 'EXPIRED', 'RELEASED', 'CANCELED')),
    CONSTRAINT chk_reservations_source CHECK (source IN ('GENERAL', 'LOTTERY'))
);

-- 만료 스케줄러가 HELD 상태 중 기한이 지난 예매를 찾는 인덱스
CREATE INDEX idx_reservations_status_hold_expires_at ON reservations (status, hold_expires_at);

-- 내 예매 목록을 최신순으로 조회하는 인덱스
CREATE INDEX idx_reservations_user_id_created_at ON reservations (user_id, created_at);

-- 예매에 묶인 좌석 한 개. 판매 여부의 기준이다(D2)
CREATE TABLE reservation_seats (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reservation_id BIGINT NOT NULL REFERENCES reservations (id),
    schedule_seat_id BIGINT NOT NULL REFERENCES schedule_seats (id),
    price NUMERIC(15, 0) NOT NULL,
    status VARCHAR(10) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by BIGINT,
    deleted_at TIMESTAMPTZ,
    deleted_by BIGINT,
    CONSTRAINT chk_reservation_seats_status CHECK (status IN ('HELD', 'CONFIRMED', 'RELEASED'))
);

-- 한 회차 좌석에 활성 예매(HELD, CONFIRMED)가 동시에 두 건 생기지 않게 막는 부분 유니크 인덱스(D2).
-- 해제된(RELEASED) 이력은 여러 건 남아도 새 선점을 막지 않는다
CREATE UNIQUE INDEX uq_reservation_seats_active_schedule_seat
    ON reservation_seats (schedule_seat_id)
    WHERE status IN ('HELD', 'CONFIRMED');

-- 예매 단위로 묶인 좌석 목록을 찾는 인덱스
CREATE INDEX idx_reservation_seats_reservation_id ON reservation_seats (reservation_id);

-- 좌석 선점 접수 요청(#17, #33). 동기형·접수형이 같은 행을 남기고 PK(request_id)로 멱등을 보장한다
CREATE TABLE seat_hold_requests (
    request_id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL,
    schedule_id BIGINT NOT NULL REFERENCES schedules (id),
    seat_ids BIGINT[] NOT NULL,
    status VARCHAR(20) NOT NULL,
    reservation_id BIGINT REFERENCES reservations (id),
    reason_code VARCHAR(40),
    created_at TIMESTAMPTZ NOT NULL,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by BIGINT,
    deleted_at TIMESTAMPTZ,
    deleted_by BIGINT,
    CONSTRAINT chk_seat_hold_requests_status CHECK (status IN ('REQUESTED', 'HELD', 'REJECTED'))
);

-- 접수 시한을 넘긴 REQUESTED 요청을 정리할 때 쓰는 인덱스
CREATE INDEX idx_seat_hold_requests_status_created_at ON seat_hold_requests (status, created_at);

-- 예매 상태 전이 기록. 한 번 쓰면 바꾸지 않는다(SA 12-1)
CREATE TABLE reservation_status_histories (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reservation_id BIGINT NOT NULL REFERENCES reservations (id),
    from_status VARCHAR(20),
    to_status VARCHAR(20) NOT NULL,
    reason VARCHAR(50),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_reservation_status_histories_reservation_id ON reservation_status_histories (reservation_id);
