-- 측정·시연용 시드 공연장(KSPO DOME, V20261030_2)을 15,000석에서 1,500석으로 줄인다. 결정: ADR 0060
-- 장비 한 대에서 15,000석 회차는 좌석 응답(약 830KB)과 부하를 서버가 버티지 못해 의미 있는 부하 측정이 되지 않았다.
--
-- 각 구역의 1~2열(30번 × 2열 = 60석)만 남기고 3~20열은 지운 것으로 표시한다(soft delete).
-- 25구역 × 60석 = 1,500석이고, 구역 A~Y와 등급 경계(A~E VIP 300석, F~O R 600석, P~Y S 600석)는 그대로다.
--
-- Note: 시드 파일(V20261030_2)을 고치면 이미 적용된 DB에서 Flyway 체크섬 검사로 기동이 실패하므로 새 마이그레이션으로 바꾼다.
-- 행을 지우지(DELETE) 않는 이유는 이 공연장으로 이미 만든 회차의 schedule_seats가 venue_seats를 참조(FK)하기 때문이다.
-- 회차 좌석 생성(insertFromVenueSeats)과 등급 조회가 deleted_at IS NULL만 읽으므로 새 회차부터 1,500석이 되고,
-- 이미 만든 회차는 15,000석 그대로 남는다.
-- 테스트가 같은 이름의 공연장을 더 만들 수 있어, 시드가 먼저 넣은 가장 작은 id의 공연장만 바꾼다.
UPDATE venue_seats
SET deleted_at = now(),
    updated_at = now()
WHERE venue_id = (SELECT min(id) FROM venues WHERE name = 'KSPO DOME')
  AND row_no > 2
  AND deleted_at IS NULL;
