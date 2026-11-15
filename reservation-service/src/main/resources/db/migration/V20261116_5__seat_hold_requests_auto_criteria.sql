-- 구역·매수만 받아 서버가 좌석을 고르는 자동 배정 선점(ISE-264). 같은 멱등키 재요청은 좌석 대신 이 조건으로 비교한다.
-- 형식은 "grade=VIP;quantity=2", "section=A;quantity=1", "any;quantity=2"다. 좌석을 직접 고른 요청은 NULL이다
ALTER TABLE seat_hold_requests ADD COLUMN auto_criteria VARCHAR(40);
