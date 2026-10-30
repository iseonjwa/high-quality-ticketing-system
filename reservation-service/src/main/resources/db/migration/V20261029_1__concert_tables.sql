-- 공연·좌석 테이블. 설계 기준: SA MASTER 12-1(공통 규칙), 12-3(reservation DB)
-- 공연장·공연·회차는 운영자가 내리는 데이터라 soft delete(deleted_at)를 쓰고,
-- 좌석은 지우지 않고 상태로만 다룬다.

CREATE TABLE venues (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by BIGINT,
    deleted_at TIMESTAMPTZ,
    deleted_by BIGINT
);

-- 공연장의 고정 좌석 배치. 회차마다 이 행을 복사해 schedule_seats를 만든다
CREATE TABLE venue_seats (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues (id),
    section VARCHAR(10) NOT NULL,
    row_no VARCHAR(5) NOT NULL,
    seat_no INT NOT NULL,
    grade VARCHAR(10) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by BIGINT,
    deleted_at TIMESTAMPTZ,
    deleted_by BIGINT,
    -- 같은 공연장에 같은 좌석이 두 번 생기지 않게 막는다
    CONSTRAINT uq_venue_seats_position UNIQUE (venue_id, section, row_no, seat_no)
);

CREATE TABLE concerts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    description TEXT,
    version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by BIGINT,
    deleted_at TIMESTAMPTZ,
    deleted_by BIGINT
);

CREATE TABLE schedules (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    concert_id BIGINT NOT NULL REFERENCES concerts (id),
    venue_id BIGINT NOT NULL REFERENCES venues (id),
    starts_at TIMESTAMPTZ NOT NULL,
    open_at TIMESTAMPTZ NOT NULL,
    max_per_user SMALLINT NOT NULL DEFAULT 4,
    hold_minutes SMALLINT NOT NULL DEFAULT 5,
    version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by BIGINT,
    deleted_at TIMESTAMPTZ,
    deleted_by BIGINT
);

-- 공연 상세에서 회차 목록을 찾는 인덱스
CREATE INDEX idx_schedules_concert_id ON schedules (concert_id);

-- 회차별 좌석. 좌석 선점의 조건부 UPDATE 대상이다(SA 12-7)
CREATE TABLE schedule_seats (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    schedule_id BIGINT NOT NULL REFERENCES schedules (id),
    venue_seat_id BIGINT NOT NULL REFERENCES venue_seats (id),
    -- 구역 조회와 접수형 파티션 키로 쓰려고 venue_seats에서 복사해 둔다
    section VARCHAR(10) NOT NULL,
    grade VARCHAR(10) NOT NULL,
    price NUMERIC(15, 0) NOT NULL,
    status VARCHAR(10) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by BIGINT,
    deleted_at TIMESTAMPTZ,
    deleted_by BIGINT,
    -- 한 회차에 같은 공연장 좌석이 두 번 생기지 않게 막는다
    CONSTRAINT uq_schedule_seats_schedule_venue_seat UNIQUE (schedule_id, venue_seat_id),
    CONSTRAINT chk_schedule_seats_status CHECK (status IN ('AVAILABLE', 'HELD', 'SOLD'))
);

-- 회차 좌석 조회를 구역 단위로 나눠 읽는 인덱스
CREATE INDEX idx_schedule_seats_schedule_id_section ON schedule_seats (schedule_id, section);
