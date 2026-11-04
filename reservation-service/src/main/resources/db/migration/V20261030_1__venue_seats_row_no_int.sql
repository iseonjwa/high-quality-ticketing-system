-- 열 번호(row_no)를 문자열에서 정수로 바꾼다. 결정 기록: ADR 0033
-- 문자열이면 정렬이 사전순(1, 10, 11, ..., 2)이 되고 '3열' 같은 값도 들어갈 수 있다.
-- V20261029_1은 이미 dev에 머지돼 적용된 환경이 있으므로 고치지 않고 이 파일로 바꾼다.
ALTER TABLE venue_seats
    ALTER COLUMN row_no TYPE INT USING row_no::int;
