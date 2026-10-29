# 예매 시스템(reservation-service) 설계 정리와 보완안 (2026-10-28)

> 기준 문서: [SA MASTER v3.2](../core/SA%20MASTER.md), [ADR 0015](../decisions/0015-queue-admission-guards.md)·[0016](../decisions/0016-payment-declined-token-reuse.md)·[0027](../decisions/0027-reservation-payment-event-contract.md)·[0029](../decisions/0029-payment-reservation-reconciliation.md)·[0030](../decisions/0030-seathold-module-and-shared-money.md)
> 기준 코드: `reservation-service` 엔티티(`Reservation`, `ReservationSeat`, `ReservationStatusHistory`, `SeatHoldRequest`, `UserScheduleQuota`, `Money`), `config-repo/reservation-service.yml`
> 상태: 팀 합의 전 검토·제안 문서다. 합의된 항목만 SA 문서와 `docs/decisions/`에 반영한다.

## 1. 예매 시스템 전체 구조 요약

예매 서비스(`reservation-service`)는 "한 좌석은 한 번만 팔린다"는 정합성을 단일 DB 트랜잭션 경계 안에서 지키기 위해 공연·좌석, 대기열, 좌석 선점, 예매·발권, 추첨을 한 서비스 안의 모듈로 둔 모듈러 모놀리스다(SA 7-1).

| 모듈 | 책임 | 소유 테이블·키 | 모듈 간 호출 규칙 (SA 7-1, ADR 0030) |
|---|---|---|---|
| `queue` | 대기열 진입(`seq` 발급), 일정 속도 입장(`seq <= admitted`), 서명 입장 토큰 발급 | Redis `queue:{scheduleId}:seq`, `waiting`, `admitted` (제안: `lastTick`, `issued`) | 인터페이스로만 호출 |
| `concert` | 공연·회차·좌석 등록, 판매 오픈 판정(`serverTime`), 좌석 `available` 조회 | `venue`, `venue_seat`, `concert`, `schedule`, `schedule_seat`, Redis 캐시 | 읽기 직접 허용 |
| `seathold` | 동기형·접수형 좌석 일괄 선점, 회차당 매수 제한, 선점 멱등 처리 | `seat_hold_requests`, `user_schedule_quotas`, Kafka `seat-hold.request` | 같은 트랜잭션 안에서 `reservation`·`concert` 도메인 직접 호출 |
| `reservation` | 예매 상태 머신, 만료 스케줄러, 결제 결과 반영(확정·발권·보상·환불 요청), 취소 | `reservations`, `reservation_seats`, `reservation_status_histories`, `tickets`, Kafka `payment.result`·`payment.command` | `seathold`를 역방향 호출하지 않음 |
| `lottery` | 추첨 설정, 응모 접수, 시드 기반 추첨·자동 배정, 빌링키 자동결제 요청, 예비자 승계 | `lottery`, `lottery_entry`, Kafka `lottery.entry` | `reservation` 직접 호출 허용 |
| `shared` | 서비스 내 공용 값 객체(`Money`, `MoneyConverter`) | — | 전 모듈에서 참조 |

### 핵심 정합성 방어선 (D1·D2, SA 12-7)
1. **회차당 매수 제한 (D1)**: `(user_id, schedule_id)`를 복합 PK로 둔 `user_schedule_quotas` 행을 `INSERT ... ON CONFLICT DO NOTHING`으로 보장한 뒤, `held_count + :n <= :maxPerUser` 조건부 UPDATE의 행 락으로 같은 사용자의 동시 선점 요청을 직렬화한다.
2. **좌석 선점 1차 판정**: `schedule_seats`에서 요청 좌석을 ID 오름차순 `FOR UPDATE`(`lock_timeout = 500ms`)로 잠그고 `status = 'AVAILABLE'`인 행만 `HELD`로 바꾼다. 변경 행 수가 요청 좌석 수(`:n`)와 다르면 롤백하고 409 `SEAT_ALREADY_HELD`를 반환한다.
3. **중복 판매 최종 방어 (D2)**: `reservation_seats`의 부분 유니크 인덱스 `UNIQUE(schedule_seat_id) WHERE status IN ('HELD', 'CONFIRMED')`가 1차 판정을 비켜 간 중복 선점을 DB 제약으로 차단한다.
4. **잠금 순서 고정 (SA 8-4)**: 모든 트랜잭션이 `매수 한도 → 회차 좌석(ID 오름차순) → 예매` 순서로 잠가 교착(Deadlock)을 방지한다.

---

## 2. 한눈에 보는 설계 허점과 수정 항목

| # | 구분 | 발견 항목 | 영향 | 권장 조치 | 협의 대상 |
|---|---|---|---|---|---|
| G1 | 치명 | 동기형 선점 실패(409) 시 트랜잭션 롤백으로 `seat_hold_requests` 행 유실 및 본문 대조 누락 | 거절 이력(`REJECTED`) 미기록, 같은 키·다른 본문(`409 IDEMPOTENCY_KEY_REUSED`) 판별 불가 | 요청 행 선행 커밋(또는 실패 시 `REQUIRES_NEW` 기록) + 기존 행 조회 시 `(userId, scheduleId, seatIds)` 대조 | 최권우 |
| G2 | 중요 | Gateway의 입장 토큰 검증 시 `POST /api/v1/reservations`는 `scheduleId`가 본문(Body)에 있음 | WebFlux Gateway에서 POST Body 파싱 비용·복잡도 발생 | Gateway는 서명·`typ`·`aud`·`exp`·`sub`만 검증, `sid == scheduleId` 대조는 Reservation에서 수행 | 권영은, 최권우 |
| G3 | 중요 | 접수형(Async) 선점 요청에 `section`이 없고 다중 구역 선택 제약이 없음 | 서로 다른 구역 좌석 동시 선택 시 `{scheduleId}:{section}` 단일 파티션 키 생성 불가 | 1회 선점은 같은 구역 내 좌석만 허용(또는 키 규칙 변경), 오픈 전(`SALE_NOT_OPEN`) 검증은 202 전에 수행 | 최권우, 반효승 |
| G4 | 중요 | 접수형 30초 타임아웃 스케줄러와 소비자의 상태 갱신 경합 | 타임아웃 직후 소비자가 돌 때 앞선 좌석·예매 변경이 롤백되지 않으면 유령 선점 발생 | 소비자 시작 시 `seat_hold_requests` 상태 선확인, 마지막 조건부 UPDATE 0행이면 전체 롤백 | 최권우 |
| G5 | 중요 | 만료·거절·취소 경합 시 `user_schedule_quotas` 선차감 후 미롤백 위험 | 잠금 순서상 쿼터를 먼저 깎고 마지막 `reservations` UPDATE가 0행일 때 쿼터 이중 차감 | `reservations` 조건부 UPDATE가 0행이면 전체 롤백 강제, 쿼터 차감에 `held_count >= :n` 조건 추가 | 반효승, 최권우 |
| G6 | 중요 | SA 8-3 결제 결과 소비에 원장 대조·재전달 방어·마지막 10초 토큰 처리 누락 | 확정 예매 재전달 시 환불 위험(ADR 0027), 만료 10초 전~만료 사이 조회 시 만료된 결제 토큰 반환 | ADR 0027 상태별 규칙 반영, `now >= hold_expires_at - 10초`이면 조회 응답에서 `paymentToken` 제외 | 반효승, 안준현 |
| G7 | 보통 | 미결제 선점(`HELD`)의 사용자 자발적 해제 경로와 취소 기한 기준 부재 | 잘못 고른 좌석을 5분 만료 전까지 바꾸지 못함(4매 한도 묶임), 취소 가능 기한 미정 | `cancel`(또는 해제 API)에서 `HELD → RELEASED` 즉시 반환 허용, 취소 마감 기준(`starts_at` 기준) 명시 | 반효승 |
| G8 | 보통 | 추첨(`lottery`) 할당 좌석 표시와 예비자 승계 시 좌석 격리 부재 | 승계 중 풀린 좌석이 일반 예매와 섞일 위험 | 추첨 `DONE` 이후에만 일반 오픈(`open_at`)이 오도록 강제, 승계는 좌석을 `AVAILABLE`로 풀지 않고 인계 | 이강민, 반효승 |
| M1 | 코드·문서 | SA 12-3은 단수 테이블명, 엔티티 코드는 복수 테이블명(`reservations` 등) | Flyway DDL과 조건부 UPDATE 쿼리 작성 시 테이블 이름 불일치 | 엔티티 코드 기준(복수형)으로 Flyway와 SA 12-3·12-7 표기 통일 | 최권우, 반효승 |
| M2 | 설정·문서 | `config-repo/reservation-service.yml`의 HikariCP `maximum-pool-size: 5` (SA 9-4는 `20`) | 동시 100명 기준선 측정 시 DB 락보다 커넥션 대기가 먼저 병목이 됨 | `maximum-pool-size: 20`으로 수정 | 최권우, 팀 |
| M3 | 문서 | 코드에 반영된 `seathold` 모듈·`shared/domain`(`Money`)이 SA 7-1·13장에 미반영 | 모듈 경계와 패키지 구조도가 실제 코드와 다름 | ADR 0030 합의 후 SA 7-1·13장 갱신 | 최권우, 반효승 |
| M4 | 문서 | 대기열 안전장치(ADR 0015)와 결제·예매 대사 내부 API(ADR 0029)가 제안 상태 | 입장 토큰 `sub` 미검증 시 타인 토큰 재사용 가능, M3 대사 검증 미확정 | 팀 확인 후 채택 전환 및 구현 반영 | 권영은, 반효승, 안준현 |

---

## 3. 설계 허점 상세와 수정안

### G1. 동기형 선점 실패 시 `seat_hold_requests` 유실 및 멱등 검증
- **현재 설계 (SA 12-3, 12-5, 12-7)**:
  - 선점 API의 멱등은 동기형·접수형 모두 `seat_hold_requests`의 PK(`request_id`)가 맡고, 거절 사유(`reason_code`)도 이 테이블에 기록한다.
  - 반면 SA 12-7의 동기형 선점은 한 트랜잭션 안에서 동작하며 매수 한도 초과나 좌석 조건부 UPDATE 실패 시 롤백한다.
- **문제**:
  1. 한 트랜잭션에서 `seat_hold_requests`를 INSERT한 뒤 409 예외로 롤백하면 `seat_hold_requests` 행도 함께 사라져 `REJECTED` 상태가 남지 않는다.
  2. 같은 `Idempotency-Key`로 다른 회차나 다른 좌석 목록을 보냈을 때 409 `IDEMPOTENCY_KEY_REUSED`를 반환해야 하나(SA 11-1, 11-3), 기존 행의 `(user_id, schedule_id, seat_ids)`를 비교하는 절차가 빠져 있다.
  3. 동기형에서 첫 요청이 아직 처리 중(`status = 'REQUESTED'`)일 때 같은 키로 중복 요청이 들어오면 어떤 응답을 줄지 정의되지 않았다.
- **수정안 `(팀 확인)`**:
  1. **요청 기록과 선점 트랜잭션 분리**:
     - **TX1**: `seat_hold_requests(REQUESTED)`를 먼저 INSERT한다. PK 충돌이 나면 기존 행을 조회해 `(user_id, schedule_id, 정렬된 seat_ids)`가 같은지 비교한다. 다르면 409 `IDEMPOTENCY_KEY_REUSED`를 반환한다. 같으면 상태에 따라 `HELD`면 기존 예매 응답(201), `REJECTED`면 기록된 `reason_code`로 409, `REQUESTED`면 처리 중 안내(409 또는 짧은 대기 후 재조회)를 반환한다.
     - **TX2 (`REQUIRES_NEW`)**: 매수 한도 조건부 UPDATE → 좌석 오름차순 조건부 UPDATE → `reservations`·`reservation_seats`·이력 INSERT → `seat_hold_requests`를 `HELD`로 갱신한다.
     - **TX3 (TX2가 409 도메인 예외로 롤백된 경우)**: `seat_hold_requests`를 `WHERE status = 'REQUESTED'` 조건으로 `REJECTED(reason_code)`로 갱신하고 409를 응답한다.

### G2. Gateway와 Reservation의 입장 토큰 검증 역할 분담
- **현재 설계 (SA 7-3, 11-4, ADR 0015)**:
  - Gateway가 좌석 조회(`GET /api/v1/schedules/{scheduleId}/seats`)와 좌석 선점(`POST /api/v1/reservations`) 앞에서 입장 토큰의 서명, `typ=entry`, `aud=gateway`, `exp`, `sub == X-User-Id`, 그리고 **토큰의 회차 ID(`sid`) == 요청 회차**를 검증한다.
- **문제**:
  - `POST /api/v1/reservations`는 URL 경로에 `scheduleId`가 없고 JSON 본문(`{ "scheduleId": 31, "seatIds": [...] }`) 안에 있다. Gateway에서 POST 본문을 파싱해 `scheduleId`를 꺼내면 반응형 스트림 버퍼링 비용이 들고 API 본문 스키마에 Gateway가 결합된다.
- **수정안 `(팀 확인)`**:
  - **방안 A (권장)**: Gateway는 입장 토큰의 서명·`typ`·`aud`·`exp`·`sub == X-User-Id`를 검증한 뒤 토큰의 회차 ID를 내부 헤더 `X-Entry-Schedule-Id`로 넘긴다(외부에서 들어온 동명 헤더는 제거). 경로에 `{scheduleId}`가 있는 `GET /schedules/{scheduleId}/seats`는 Gateway에서 바로 대조하고, `POST /reservations`는 Reservation 서비스가 `X-Entry-Schedule-Id == request.scheduleId`를 대조해 다르면 403 `ENTRY_TOKEN_INVALID`를 반환한다.
  - **방안 B**: 클라이언트가 선점 요청 때도 `X-Schedule-Id` 헤더를 함께 보내거나 경로를 `/api/v1/schedules/{scheduleId}/reservations`로 둔다(API 경로 변경이 필요하므로 방안 A가 영향이 작다).

### G3. 접수형(Async) 선점의 구역(`section`) 결정과 사전 검증
- **현재 설계 (SA 10-1, 10-3, 11-5, 12-3)**:
  - 접수형 토픽 `seat-hold.request`의 키는 `{scheduleId}:{section}`이고 페이로드(`SeatHoldRequested`)에도 `section`이 있다.
  - API 요청 본문은 `{ "scheduleId": 31, "seatIds": [1023, 1024] }`이며, 접수형 API는 좌석 행을 잠그지 않고 `seat_hold_requests`와 `outbox`만 저장한다.
- **문제**:
  1. 사용자가 한 번에 서로 다른 구역 좌석(예: A구역 1석, B구역 1석)을 선택하면 단일 `{scheduleId}:{section}` 키를 만들 수 없다. 서로 다른 파티션으로 나뉘면 일괄 선점(전부 아니면 전부) 원자성이 깨지고, 한 파티션으로 보내면 다른 구역 요청과 락이 섞여 구역 파티셔닝 목적이 깨진다.
  2. 요청 본문에 `section`이 없으므로 `outbox.message_key`를 채우려면 API 단계에서 좌석의 `section`을 알아야 한다.
  3. 오픈 전 호출(`SALE_NOT_OPEN`, E4)이나 좌석 수 오류(`SEAT_COUNT_MISMATCH`)를 소비자까지 넘기면 오픈 전에 호출했는데도 202가 반환되어 E4 기대 결과(즉시 409 거절)와 어긋난다.
- **수정안 `(팀 확인)`**:
  1. **단일 구역 선택 규칙**: 1회 선점 요청의 `seatIds`는 모두 같은 `schedule_id`와 같은 `section`에 속해야 한다. 구역이 섞여 있거나 해당 회차의 좌석이 아니면 400 `VALIDATION_FAILED`(또는 `SEAT_COUNT_MISMATCH`)로 거절한다.
  2. **접수형 API의 가벼운 사전 검증**: 접수형에서도 ① 좌석 수 1~4개·중복 없음 검증, ② 회차 오픈 시각(`open_at <= now`) 검증(`SALE_NOT_OPEN`), ③ `schedule_seats`의 락 없는 조회(또는 캐시)로 좌석 존재·단일 `section` 추출을 수행한 뒤 `seat_hold_requests` + `outbox(key = scheduleId:section)`를 저장한다.

### G4. 접수형 30초 타임아웃 스케줄러와 소비자의 경합 순서
- **현재 설계 (SA 10-5, 12-3)**:
  - 소비자는 선점 시도(quota → 좌석 → 예매) 후 `seat_hold_requests`를 `WHERE status = 'REQUESTED'` 조건부로 갱신하고 `processed_event`를 기록한다.
  - 30초가 넘은 `REQUESTED`는 재확인 스케줄러가 `REJECTED(TIMEOUT)`으로 닫는다.
- **문제**:
  - 소비자가 좌석과 예매를 `HELD`로 만든 뒤 마지막에 `seat_hold_requests`를 갱신할 때 이미 스케줄러가 `REJECTED(TIMEOUT)`으로 바꿨다면 갱신 행 수가 0이 된다. 이때 앞선 좌석·예매 변경을 커밋하면 요청은 `TIMEOUT`인데 좌석은 `HELD`로 묶인다.
- **수정안**:
  1. 소비자 트랜잭션 시작 시 `seat_hold_requests`를 먼저 조회(또는 `FOR UPDATE`)해 이미 `REQUESTED`가 아니면 좌석을 건드리지 않고 `processed_event`만 기록한 뒤 종료한다.
  2. 선점 성공/거절 후 `UPDATE seat_hold_requests ... WHERE request_id = :id AND status = 'REQUESTED'` 결과가 0행이면 트랜잭션 전체를 롤백한다.

### G5. 해제(만료·거절·취소) 시 `user_schedule_quotas` 이중 차감 방어
- **현재 설계 (SA 8-3, 8-4)**:
  - 잠금 순서가 `매수 한도 → 회차 좌석 → 예매`이므로, 만료 스케줄러(`HELD → EXPIRED`)와 결제 거절 소비자(`HELD → RELEASED`)도 이 순서로 잠근다.
- **문제**:
  - 같은 예매에 만료 스케줄러와 결제 거절 소비자가 동시에 진입해 `user_schedule_quotas`를 먼저 `-n` 감소시키고 마지막에 `reservations`를 `WHERE status = 'HELD'`로 갱신하면, 늦은 트랜잭션은 `reservations` 갱신이 0행이 된다. 이때 롤백하지 않으면 다른 유효 예매의 매수 한도까지 깎이거나 음수 제약 위반이 발생한다.
- **수정안**:
  - 해제 경로(만료·거절·취소)의 쿼리는 아래 두 규칙을 반드시 지킨다.
    1. `UPDATE user_schedule_quotas SET held_count = held_count - :n WHERE user_id = :userId AND schedule_id = :scheduleId AND held_count >= :n`
    2. 마지막 `UPDATE reservations SET status = :toStatus WHERE id = :id AND status = :fromStatus`의 영향 행이 0이면 **트랜잭션 전체를 롤백**(또는 `RollbackOnly` 표시)해 앞선 쿼터·좌석 변경을 되돌린다.

### G6. 결제 결과 이벤트 처리 계약과 결제 토큰 만료 경계
- **현재 설계 (SA 7-3, 8-3, 11-5 vs ADR 0027)**:
  - SA 8-3에는 `EXPIRED`에 `PaymentApproved`가 올 때만 자동 환불(`RefundRequested`)이 적혀 있다.
  - `GET /api/v1/reservations/{id}`는 예매가 `HELD`일 때 `paymentToken`(만료 = `hold_expires_at - 10초`)을 내려준다.
- **문제**:
  1. 이미 `CONFIRMED`인 예매에 같은 `paymentId`의 `PaymentApproved`가 재전달될 때 환불을 요청하면 확정 티켓의 결제가 환불된다.
  2. `RELEASED`·`CANCELED` 상태나 다른 `paymentId`의 중복 승인, 금액·사용자 불일치 승인에 대한 환불 처리가 SA 8-3에 빠져 있다.
  3. 선점 만료 10초 전(`hold_expires_at - 10초 <= now < hold_expires_at`)에 예매 상세를 조회하면 예매는 아직 `HELD`이지만 이미 만료된(`exp <= now`) 결제 토큰이 내려간다.
- **수정안**:
  1. [ADR 0027](../decisions/0027-reservation-payment-event-contract.md)의 상태별 처리 표(원장 `user_id`·`total_amount`·`payment_id` 대조, 같은 `paymentId` 멱등 무시, 확정 불가 승인 환불, `CONFIRMED` + `PaymentRefunded` 자동 좌석 해제 금지)를 채택해 SA 8-3을 갱신한다.
  2. `GET /api/v1/reservations/{id}`에서 `now >= hold_expires_at - 10초`이면 `paymentToken`을 `null`로 내려 만료된 토큰으로 결제를 시도하지 않게 한다.

### G7. 미결제 선점(`HELD`) 자발적 해제와 취소 가능 기한
- **현재 설계 (SA 8-3, 11-3, 11-4)**:
  - `POST /api/v1/reservations/{id}/cancel`은 `CONFIRMED → CANCELED`와 환불 요청 발행만 다룬다.
- **문제**:
  - 사용자가 좌석을 잘못 선택해 다시 고르고 싶을 때 `HELD` 예매를 즉시 해제할 수 없어 5분 만료 때까지 `user_schedule_quotas`(4매 한도)가 묶인다. 또한 `409 RESERVATION_NOT_CANCELABLE`의 기한 기준이 없다.
- **수정안 `(팀 확인)`**:
  - 취소 API(또는 선점 해제)에서 `HELD` 상태인 예매도 요청할 수 있게 열어, 진행 중인 결제가 없다면 즉시 `HELD → RELEASED`(좌석 `AVAILABLE`, 쿼터 `-n`, 환불 이벤트 없음)로 전이하거나, `CONFIRMED` 예매의 취소 가능 기한을 회차 시작 시각(`now < starts_at`) 기준으로 명시한다.

### G8. 추첨(`lottery`) 좌석과 일반 예매 좌석의 격리
- **현재 설계 (SA 4-3, 12-3)**:
  - 추첨은 일반 예매 오픈 전에 진행하며 남은 좌석은 일반 예매로 넘긴다. `schedule_seats.status`는 `AVAILABLE · HELD · SOLD` 세 가지다.
- **문제**:
  - 추첨과 예비자 승계가 끝나기 전에 일반 예매 `open_at`이 도래하면 일반 예매자가 추첨용 좌석을 선점할 수 있고, 예비자 승계 중 이전 당첨자의 예매가 풀릴 때 좌석을 `AVAILABLE`로 두면 경합이 생긴다.
- **수정안 `(팀 확인, U9)`**:
  - 추첨 생성 시 `draw_at`과 승계 마감 시각이 회차의 일반 예매 `open_at`보다 앞서는지 검증하고, 예비자 승계 시에는 좌석을 `AVAILABLE`로 풀지 않고 같은 트랜잭션에서 다음 예비자의 신규 `HELD` 예매로 좌석 소유권을 바로 넘긴다.

---

## 4. 코드·설정·문서 불일치 수정 목록

### M1. 테이블 이름 단수/복수 통일
- **불일치**: SA 12-3·12-7은 단수형(`reservation`, `reservation_seat`, `seat_hold_request`, `user_schedule_quota`, `reservation_status_history`)을 쓰지만, 실제 엔티티 코드(`Reservation.java`, `ReservationSeat.java`, `SeatHoldRequest.java`, `UserScheduleQuota.java`, `ReservationStatusHistory.java`)의 `@Table(name = ...)`은 모두 **복수형**(`reservations`, `reservation_seats`, `seat_hold_requests`, `user_schedule_quotas`, `reservation_status_histories`)이다(`user-service`의 `users`와 같은 규칙).
- **조치**: Flyway 마이그레이션 스크립트와 SA 12-3·12-7의 SQL을 엔티티 코드에 맞춰 **복수형**으로 통일한다.

### M2. `reservation-service` DB 커넥션 풀 설정 수정
- **불일치**: SA 9-4는 커넥션 풀 초기값을 서비스당 `20`으로 정했으나, `config-repo/reservation-service.yml`에는 `maximum-pool-size: 5`로 설정되어 있다.
- **조치**: `config-repo/reservation-service.yml`의 `spring.datasource.hikari.maximum-pool-size`를 `20`으로 수정해 M2 동기형 기준선 측정 시 커넥션 풀 병목으로 인한 측정 왜곡을 막는다.

### M3. `seathold` 모듈 분리 및 `shared/domain` 패키지 반영
- **불일치**: [ADR 0030](../decisions/0030-seathold-module-and-shared-money.md)에 따라 코드에는 `com.iseonjwa.reservation.seathold`와 `com.iseonjwa.reservation.shared.domain`(`Money`, `MoneyConverter`)이 분리되어 있으나, SA 7-1 모듈 규칙과 13장 패키지 구조도에는 아직 반영되지 않았다.
- **조치**: 반효승 님과 경계 확인 후 SA 7-1과 13장을 갱신한다.

### M4. 제안 상태인 ADR 확정
- 대기열 안전장치([ADR 0015](../decisions/0015-queue-admission-guards.md)), 결제↔예매 이벤트·원장 대조 계약([ADR 0016](../decisions/0016-payment-declined-token-reuse.md), [ADR 0027](../decisions/0027-reservation-payment-event-contract.md)), 결제·예매 내부 대사 API([ADR 0029](../decisions/0029-payment-reservation-reconciliation.md))를 담당자 간 합의를 거쳐 확정한다.
