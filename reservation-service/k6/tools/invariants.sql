-- 측정 한 회가 끝난 뒤 회차 하나의 정합성(대사) 확인. 모두 0이 정상이다.
-- 사용: psql -v sid=<회차 ID> -f invariants.sql
-- 기준은 SeatHoldFixture(동시성 테스트)와 같다. 판매 여부의 기준은 reservation_seats다(대사 D2).

-- 한 좌석에 활성 예매 좌석이 2건 이상(중복 판매)
SELECT 'double_sold_seats' AS check_name, count(*) AS violations
FROM (SELECT rs.schedule_seat_id
      FROM reservation_seats rs JOIN schedule_seats ss ON ss.id = rs.schedule_seat_id
      WHERE ss.schedule_id = :sid AND rs.status IN ('HELD', 'CONFIRMED')
      GROUP BY rs.schedule_seat_id HAVING count(*) > 1) d
UNION ALL
-- 사용자 매수 기록(held_count)과 실제 활성 좌석 수가 다른 사용자
SELECT 'quota_mismatch_users', count(*)
FROM (SELECT q.user_id
      FROM user_schedule_quotas q
      LEFT JOIN (SELECT r.user_id, count(*) AS active
                 FROM reservation_seats rs JOIN reservations r ON r.id = rs.reservation_id
                 WHERE r.schedule_id = :sid AND rs.status IN ('HELD', 'CONFIRMED')
                 GROUP BY r.user_id) a ON a.user_id = q.user_id
      WHERE q.schedule_id = :sid AND q.held_count <> COALESCE(a.active, 0)) m
UNION ALL
-- 좌석 상태(HELD)와 활성 선점 원장이 어긋난 좌석
SELECT 'seat_status_mismatches', count(*)
FROM schedule_seats ss
WHERE ss.schedule_id = :sid
  AND (ss.status = 'HELD') <> EXISTS (SELECT 1 FROM reservation_seats rs
                                      WHERE rs.schedule_seat_id = ss.id AND rs.status = 'HELD')
UNION ALL
-- 참고: 이 회차의 활성 선점 좌석 수와 마지막 선점 성공 시각(매진 시각 판단용)
SELECT 'held_seats', count(*)
FROM reservation_seats rs JOIN schedule_seats ss ON ss.id = rs.schedule_seat_id
WHERE ss.schedule_id = :sid AND rs.status IN ('HELD', 'CONFIRMED');

SELECT max(r.created_at) AS last_hold_at FROM reservations r WHERE r.schedule_id = :sid;
