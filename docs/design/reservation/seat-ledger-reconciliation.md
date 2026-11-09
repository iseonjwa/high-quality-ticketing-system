# 좌석 원장 대사 (ISE-14)

상태: 구현 완료, 자동 실행 여부는 논의 대기 (2026-11-09, 좌석 선점 담당 최권우)

부하·장애 주입 테스트 뒤 회차 좌석 상태와 보유 매수가 원장과 어긋나지 않았는지 확인하고, 어긋났으면 원장 기준으로 고친다(SA 3장, 6-4, 12-7 D2). 만료 스케줄러와 결제 결과 소비자는 어긋남을 만나면 롤백하지 않고 경고만 남기는데([hold-expiry.md](hold-expiry.md) 2절 6번), 그 어긋남을 이 대사가 고친다.

## 1. 기준

판매 여부의 기준은 활성 예매 좌석(`reservation_seats`의 `HELD`, `CONFIRMED`)이다. 부분 유니크 인덱스 때문에 한 좌석의 활성 예매 좌석은 많아야 한 건이다. 대사는 이 원장을 바꾸지 않고, 원장에서 계산한 값으로 아래 두 값만 고친다.

| 대상 | 원장에서 계산한 값 |
|---|---|
| `schedule_seats.status` | 활성 예매 좌석이 `CONFIRMED`면 `SOLD`, `HELD`면 `HELD`, 없으면 `AVAILABLE` |
| `user_schedule_quotas.held_count` | 그 사용자·회차의 활성 예매 좌석 수. 행이 없으면 0으로 본다 |

- 보유 매수는 `CONFIRMED`도 센다. 지금 코드에서 결제 확정은 보유 매수를 빼지 않고, 선점 해제(만료, 결제 거절)만 뺀다. 확정한 좌석도 1인 매수 한도에 계속 들어간다는 뜻이다. [hold-expiry.md](hold-expiry.md) 2절 3번이 이 규칙을 바꾸면 대사 기준도 함께 바꾼다.
- 예매 취소(ISE-206)가 생기면 취소는 예매 좌석을 `RELEASED`로 바꾸고 보유 매수를 빼야 한다. 그래야 대사 기준과 맞는다.

## 2. 동시성

찾는 쿼리와 고치는 쿼리 사이에 선점·만료·결제 확정이 커밋될 수 있다. 찾은 값을 그대로 덮어쓰면, 방금 맞게 바뀐 행을 낡은 값으로 되돌릴 수 있다.

그래서 한 건마다 트랜잭션을 따로 연다.
1. 고칠 행(회차 좌석 또는 보유 매수)을 `FOR UPDATE`로 잠근다. 보유 매수 행이 없으면 0으로 만든 뒤 잠근다.
2. 원장을 다시 읽어 기대값을 계산한다.
3. 지금 값이 기대값과 다를 때만 바꾼다. 0행이면 이미 맞춰진 것이고, 고친 수에 넣지 않는다.

좌석이나 매수를 바꾸는 트랜잭션(선점, 만료, 결제 확정·거절)은 모두 같은 트랜잭션에서 그 행도 함께 바꾼다. 잠금을 얻은 뒤에 실행한 새 문장은 그 트랜잭션이 커밋한 결과를 본다(READ COMMITTED는 문장마다 스냅샷을 새로 잡는다). 대사 트랜잭션은 행 하나만 잠그므로, 서로 상대의 잠금을 기다리는 교착은 생기지 않는다.

테스트(`SeatLedgerReconciliationTest`)는 선점·만료가 두 스레드에서 도는 동안 대사를 계속 돌렸다. 대사가 고친 건수가 0이고 끝난 뒤 어긋남도 0인 것을 확인했다. 이 테스트는 "맞는 원장을 잘못 고치지 않는다"를 보여 준다. 실제 어긋남과 동시 쓰기가 같은 행에서 겹치는 경우는 재현하지 않았다.

## 3. 사용법

reservation-service 내부 API다. `/internal/**`은 Gateway가 밖에서 막으므로 내부망에서 부른다. 다른 서비스가 부르지 않는 운영용 API라 서비스 간 계약이 아니다.

| 요청 | 하는 일 |
|---|---|
| `GET /internal/seat-holds/schedules/{scheduleId}/ledger` | 고치지 않고 어긋난 좌석(`seatMismatches`)과 보유 매수(`heldCountMismatches`)를 돌려준다 |
| `POST /internal/seat-holds/schedules/{scheduleId}/ledger/reconcile` | 원장 기준으로 고치고, 찾은 수·고친 수와 남은 어긋남(`remaining`)을 돌려준다 |

- 한 번에 종류마다 최대 1,000건을 다룬다. `remaining`이 비지 않았으면 다시 부른다.
- 고친 건마다 `event=SEAT_LEDGER_REPAIRED` WARN 로그를 남긴다(대상, 회차, 좌석 또는 사용자, 바꾸기 전·뒤 값). 감사자(`updated_by`)는 NULL(시스템)이다.
- 부하 테스트 판정에는 `GET`을 쓴다. 어긋남 0건이 완료 기준이다. 측정 중에는 부르지 않는다. 회차 전체를 집계하므로 측정 부하에 섞인다.

```bash
# local 스택에서 예시. 컨테이너 안에서 부른다(서비스 포트 8082, config-repo/reservation-service.yml)
docker exec iseonjwa-reservation-service-1 curl -s http://localhost:8082/internal/seat-holds/schedules/1/ledger
docker exec iseonjwa-reservation-service-1 curl -s -X POST http://localhost:8082/internal/seat-holds/schedules/1/ledger/reconcile
```

## 4. 코드 위치

| 역할 | 파일 |
|---|---|
| 대사 서비스 | `reservation-service/.../seathold/application/SeatLedgerReconciliationService.java` |
| 조회·보정 쿼리 | `seathold/domain/repository/SeatLedgerRepository.java`, `seathold/infrastructure/persistence/JdbcSeatLedgerRepository.java` |
| 내부 API | `seathold/presentation/SeatLedgerReconciliationController.java` |
| 통합 테스트 | `reservation-service/src/test/.../seathold/application/SeatLedgerReconciliationTest.java` |

## 5. 논의할 것

| # | 항목 | 상대 | 현재 | 정해야 할 것 |
|---|---|---|---|---|
| 1 | 자동 실행 | 팀 | 사람이나 측정 도구가 부를 때만 돈다 | 주기 실행을 둘지 정한다. 두면 찾기만 하고 알림(Loki 규칙)을 보낼지, 고치기까지 할지도 정한다. 측정 중에는 꺼야 한다 |
| 2 | `held_count`가 무엇을 세는지 | 반효승 | `CONFIRMED`도 센다(지금 코드 기준) | [hold-expiry.md](hold-expiry.md) 2절 3번. 바뀌면 1절 기준을 고친다 |
| 3 | 예매 상태와 예매 좌석 상태 | 반효승 | 대사 대상이 아니다 | `reservations.status`와 `reservation_seats.status`가 어긋나는 경우(예매는 EXPIRED인데 좌석이 HELD)도 볼지 정한다. 원장 쪽 불일치라 자동으로 고치지 않고 찾기만 하는 편이 안전하다 |
