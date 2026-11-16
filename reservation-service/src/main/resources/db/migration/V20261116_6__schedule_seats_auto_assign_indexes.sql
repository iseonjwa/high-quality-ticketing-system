-- 자동 배정(ADR 0059)이 회차 안에서 좌석 ID 순으로 빈 좌석을 찾게 한다.
-- (schedule_id, section)만 있을 때 구역 없는 요청(회차 전체·등급)은 pk(id) 인덱스를 처음부터 훑어
-- 다른 회차 좌석 249만 행을 걸러 냈다(로컬 재현 300ms대, TS-72).
-- status는 인덱스에 넣지 않는다. 선점·만료가 status만 바꾸므로 HOT 갱신이 그대로 남는다.
-- Note: CREATE INDEX는 만드는 동안 schedule_seats 쓰기를 막는다(SHARE 잠금). 249만 행 로컬 측정은
-- 세 개 합쳐 약 2.8초였다. Flyway가 이 서비스 기동 때 한 트랜잭션으로 돌리므로 CONCURRENTLY는 쓰지 않는다.
CREATE INDEX idx_schedule_seats_schedule_id_id ON schedule_seats (schedule_id, id);
CREATE INDEX idx_schedule_seats_schedule_id_section_id ON schedule_seats (schedule_id, section, id);
CREATE INDEX idx_schedule_seats_schedule_id_grade_id ON schedule_seats (schedule_id, grade, id);
