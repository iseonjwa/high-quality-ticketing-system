-- 측정·시연용 공연장 시드. 설계 기준: SA MASTER 3-2(KSPO DOME 약 15,000석), 6-5, 12-9
-- 실제 배치 대신 단순 격자(25구역 × 20열 × 30번 = 15,000석)로 만든다.
-- 구역 A~E는 VIP(3,000석), F~O는 R(6,000석), P~Y는 S(6,000석)다.
-- 구역은 접수형 선점의 파티션 키에 쓰이므로 한 구역(600석)에 요청이 몰리지 않게 25개로 나눈다.

-- 공연장을 넣고 새로 생긴 id를 돌려받는다(RETURNING).
-- id는 DB가 정하므로 1이라고 가정해 박아 넣지 않고 아래 좌석 INSERT에서 venue.id로 쓴다.
WITH venue AS (
    INSERT INTO venues (name, created_at, updated_at)
    VALUES ('KSPO DOME', now(), now())
    RETURNING id
)
INSERT INTO venue_seats (venue_id, section, row_no, seat_no, grade, created_at, updated_at)
SELECT venue.id,
       -- 구역 번호 0~24를 문자 A~Y로 바꾼다(0 → A, 1 → B, ... 24 → Y)
       chr(ascii('A') + section_index),
       row_index,
       seat_index,
       -- 무대에 가까운 앞 구역일수록 높은 등급이다: 0~4 VIP, 5~14 R, 15~24 S
       CASE
           WHEN section_index < 5 THEN 'VIP'
           WHEN section_index < 15 THEN 'R'
           ELSE 'S'
       END,
       now(),
       now()
-- 쉼표로 나열한 generate_series는 모든 조합을 만든다(CROSS JOIN).
-- 구역 25개 × 열 20개 × 번호 30개 = 15,000행이 문장 하나로 생긴다.
FROM venue,
     generate_series(0, 24) AS section_index,
     generate_series(1, 20) AS row_index,
     generate_series(1, 30) AS seat_index;
