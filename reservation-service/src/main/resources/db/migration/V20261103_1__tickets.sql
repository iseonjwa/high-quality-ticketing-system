-- 좌석당 티켓 한 장. 결제 승인으로 예매가 CONFIRMED가 될 때 예매 좌석마다 발급한다(SA 8-3, 12-3)
CREATE TABLE tickets (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_no VARCHAR(20) NOT NULL,
    reservation_seat_id BIGINT NOT NULL REFERENCES reservation_seats (id),
    status VARCHAR(10) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by BIGINT,
    deleted_at TIMESTAMPTZ,
    deleted_by BIGINT,
    CONSTRAINT uq_tickets_ticket_no UNIQUE (ticket_no),
    -- 같은 승인 이벤트가 두 번 처리돼도 좌석당 티켓이 한 장을 넘지 않게 막는다
    CONSTRAINT uq_tickets_reservation_seat_id UNIQUE (reservation_seat_id),
    CONSTRAINT chk_tickets_status CHECK (status IN ('ISSUED', 'VOID'))
);
