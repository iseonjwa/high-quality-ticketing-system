# 추첨 상세 설계안

상태: 초안. [options.md](options.md)의 결정(D2~D14)과 나머지 추천안을 고른다고 가정한 상세다. 테이블 변경은 SA에 아직 반영하지 않았다(README 5절 일괄 반영 목록). 추천과 다른 안을 고르면 해당 절을 고친다. 숫자는 모두 `[가정]`이다.

## 1. 테이블 변경안 (SA 12-3 기준)

**lotteries**: 추가 컬럼만 적는다.

| 컬럼 | 타입 | 제약 | 이유 |
|---|---|---|---|
| waitlist_count | INT | NOT NULL | 예비자 수. 기본 당첨자의 50%(D1) |
| `grade` → `source_grade` | VARCHAR(10) | NOT NULL | 추첨석을 떼어 온 등급(기본 S). 종결 때 되돌릴 등급(D9) |
| 제약 교체 | | `UNIQUE(schedule_id, grade)` → `UNIQUE(schedule_id)` | 회차당 추첨 1개(D2, D9) |

- `seed`는 설정 때 `SecureRandom`으로 채우고 설정 뒤 바꾸지 못하게 막는다(D7). 공개하지 않는다. 시드 약속을 확장할 때 `seed_hash CHAR(64)`를 더하고 응모 시작 전에 공개한다.
- 설정 검증: `entry_start_at < entry_end_at <= draw_at`, `source_grade`의 AVAILABLE 좌석이 `seat_count` 이상, `source_grade <> 'LOTTERY'`.
- 설정 시점: 설정·수정 모두 `now() < LEAST(open_at, entry_start_at)`. 어기면 409(D9 후속).
- 설정 경고: `draw_at`이 저트래픽 시간대(예: 03:00~05:00 `[가정]`) 밖이면 응답에 경고를 담는다. 막지는 않는다(D10 운영 정책).
- 설정 수정: `now() < entry_start_at`일 때만. 좌석 수를 바꾸면 `LOTTERY` 좌석을 늘리거나 `source_grade`로 되돌린다(D9).

**schedule_seats**: 컬럼 변경 없음. `grade`에 값 `LOTTERY`가 더해진다(D9). 설정 API만 쓰고, 좌석 생성 API는 받지 않는다. 일반 선점은 `grade <> 'LOTTERY'` 좌석만 잡는다(좌석 담당 합의).

**lottery_entries**

| 변경 | 내용 | 이유 |
|---|---|---|
| 제약 유지 | `UNIQUE(lottery_id, user_id)` | 1인 1회차 1응모(회차당 추첨 1개라 추첨별 제약이 곧 회차별 제약). 취소한 행도 남아 재응모를 막는다(D2, D5) |
| 상태 추가 | `CANCELED` | D5 |
| 추가 | `payment_attempts SMALLINT NOT NULL DEFAULT 0` | 우리 책임 실패 1회 재시도(D12) |
| 추가 | `fail_reason VARCHAR(30)` | FAILED 사유. 결과 조회와 지표에 쓴다 |
| 인덱스 | `(lottery_id, status, draw_rank)` | 승계 대상 찾기(D13) |
| 인덱스 | `(lottery_id) WHERE status = 'WON' AND reservation_id IS NULL` | 배정 워커(D10) |

- `request_id UNIQUE`가 응모 멱등을 맡는다. 같은 키 재요청은 기존 행을 돌려주고, 같은 키에 다른 `lotteryId`·`quantity`면 409다. 그래서 응모는 `idempotency_records`를 쓰지 않는다. 선점의 `seat_hold_requests` PK와 같은 방식이다.

**reservations**: 변경 없음. `source = 'LOTTERY'`, `hold_expires_at = 생성 + 30분`(D10).

## 2. API

| 메서드·경로 | 권한 | 응답 | 비고 |
|---|---|---|---|
| POST `/api/v1/admin/schedules/{scheduleId}/lotteries` | ADMIN | 201 | 같은 트랜잭션에서 `source_grade` 좌석 `seatCount`개를 `LOTTERY`로 바꾼다(D9). 본문: `sourceGrade`, `seatCount`, `maxPerEntry`, `entryStartAt`, `entryEndAt`, `drawAt`, `waitlistCount`. |
| PATCH `/api/v1/admin/lotteries/{lotteryId}` | ADMIN | 200 | 추가 제안(D9). 응모 시작 전이고 일반 오픈(`open_at`) 전만. `sourceGrade`, `seatCount`, 일정 수정 |
| POST `/api/v1/lotteries/{lotteryId}/entries` | 인증, `Idempotency-Key` | 201(D3 A안), SA는 202 | 본문: `quantity`. 대기열·입장 토큰을 거치지 않는다 |
| DELETE `/api/v1/lotteries/{lotteryId}/entries/me` | 인증 | 200 | 추가 제안(D5). 마감 전만. 취소 뒤 재응모는 409 |
| GET `/api/v1/lotteries/{lotteryId}/entries/me` | 인증 | 200 | `status`, `drawRank`(예비 번호), `reservationId`, `failReason`. 알림(F7) 전까지 결과 안내의 기본 경로다. 결제 상태가 미확정(NMC)이면 "결제 확인 중", 선점 만료 뒤 늦은 승인이면 "환불 예정"을 담는다(D14) |
| GET `/api/v1/lotteries/{lotteryId}` | 공개 | 200 | 추가 제안(D7). `status`, 응모·추첨 일정, 좌석 수, 응모 규칙(1~2매, 연석 미보장, 재응모 불가). 확장 때 `seedHash`, DRAWN 이후 `seed` |

응모 INSERT는 기간을 SQL 안에서 확인한다. 예: `INSERT ... SELECT ... FROM lotteries WHERE id = ? AND status = 'OPEN' AND now() >= entry_start_at AND now() < entry_end_at`. 0행이면 `LOTTERY_NOT_OPEN`, 유니크 위반이면 `LOTTERY_ALREADY_ENTERED`다. 마감 판정이 DB 시각 하나라 서버마다 시계가 달라도 어긋나지 않는다.

## 3. 이벤트

| 이벤트 | 토픽 | 발행 | 변경 |
|---|---|---|---|
| `BillingPaymentRequested` | SA 그대로 `payment.command`(D6 기준선). 전용 토픽은 시나리오 2 뒤 개선 후보 | 배정 워커 트랜잭션의 outbox | 필드는 SA 10-3 그대로(`reservationId`, `userId`, `amount`, `lotteryEntryId`). 키는 `reservationId` |
| `PaymentApproved`·`PaymentDeclined` | `payment.result` | Payment | 변경 없음. 소비자가 `source`로 분기(D11) |
| `BillingKeyRegistered`·`BillingKeyRevoked` | 미정 | Payment | D4 2단계에서 추가. 필드 `userId`, `cardLast4` |
| `LotteryEntryRequested` | `lottery.entry` | — | D3 A안이면 쓰지 않는다 |

- 자동결제 멱등: 결제 쪽 `UNIQUE(reservation_id)`와 `processed_events`로 막는다. 재시도(D12)는 새 예매라 새 `reservationId`다.
- 금액: `amount`는 예매 `total_amount`다. 결과를 받을 때 Reservation이 원장과 다시 대조한다(ADR 0027).
- 늦게 도착한 요청: 결과를 받지 못한 당첨 예매는 새 예매를 만들지 않고 선점을 연장한다(D12). 같은 `reservationId`라 늦게 도착한 요청도 결제 멱등이 한 번만 처리한다. 대안으로 이벤트에 `expiresAt`을 넣어 Payment가 지난 요청을 PG 호출 전에 거절하는 방법이 있다(일반 결제의 결제 토큰 `exp`와 같은 역할). 결제 쪽 변경과 서버 간 시계 차이가 생겨 기본안에서는 뺐다. 결제 담당이 방어를 한 겹 더 원하면 추가한다.

## 4. 스케줄러와 워커
모두 인스턴스마다 돌아도 안전하다. 전이는 조건부 UPDATE, 행 처리는 `SKIP LOCKED`다.

| 이름 | 주기 | 대상 | 하는 일 |
|---|---|---|---|
| 마감 | 10초 | `OPEN AND entry_end_at <= now()` | OPEN → CLOSED. 늦어도 응모 INSERT 조건이 막으므로 결과가 바뀌지 않는다 |
| 추첨 | 10초 | `CLOSED AND draw_at <= now()` | 한 트랜잭션으로 순위·WON·WAITLISTED·LOST, CLOSED → DRAWN(D8). WON·WAITLISTED만 배치 갱신, LOST는 한 문장. `timeout = 10`초 |
| 배정 워커 | 1초 | `WON AND reservation_id IS NULL` | 1명 = 1트랜잭션: 매수 한도 → 좌석(ID 오름차순) → 예매 HELD → 응모 연결 → outbox(D10) |
| 승계 워커 | 1초 | `DRAWN AND draw_at + 30분 > now()` 추첨 행 `FOR UPDATE SKIP LOCKED` | 남은 좌석(`seat_count - WON·PAID 매수 합`)만큼 WAITLISTED를 순위 순으로 WON(매수 건너뛰기 규칙 동일). 예매는 배정 워커가 만든다(D13) |
| 승계 마감 | 10초 | `DRAWN AND draw_at + 30분 <= now()` | 남은 WAITLISTED → LOST. 승계 쿼리도 같은 시각 조건을 가져 마감 뒤 승계가 없다 |
| 종결 | 1분 | `DRAWN`이고 미종결 WON이 없음 | 한 트랜잭션: DRAWN → DONE, 그 회차 `LOTTERY` 좌석을 상태와 상관없이 `source_grade`로 되돌림(D9) |

기존 코드에 붙는 훅 두 곳(예매 흐름 담당과 합의):
- `payment.result` 소비자: `source = LOTTERY`면 lottery 처리기 호출(D11).
- 예매 만료 스케줄러: `source = LOTTERY` 예매가 만료되면 lottery 처리기 호출. 결제 결과를 받지 못한 만료는 만료 대신 선점을 1회 연장한다(상한: 승계 마감 + 30분, D12).
- 우리 책임 실패의 재시도는 응모 행의 `reservation_id`를 비워 배정 워커에 다시 맡기는 방식이다(D12 `WON → WON`). 응모 행은 현재 예매만 가리키므로, 이전 예매는 `reservations`(source LOTTERY, 같은 사용자)와 예매 상태 이력에서 찾는다.

모듈 경계(D11, 서비스 분리 대비). 모듈 규칙 `#14`의 직접 호출 허용 안에서 호출을 인터페이스 한 곳으로 모은다. DB와 트랜잭션은 공유한다.

| 규칙 | 내용 | 분리할 때 |
|---|---|---|
| 포트로만 부른다 | lottery → 예매·좌석은 `SystemHoldPort`(D10 배정), `LotterySeatPort`(D9 전환·반환), `LotteryReservationQueryPort`(D12 이전 예매 조회). 예매 → lottery는 `LotteryPaymentResultHandler`(`onPaid`, `onFailed`, `onExpired`)만. 이름은 예시다 | 포트 구현을 outbox 이벤트·HTTP 클라이언트로 바꾼다 |
| 인자는 ID와 값만 | 엔티티 대신 `reservationId`, `reasonCode` 같은 값 | 인자가 그대로 이벤트 본문이 된다 |
| 처리기는 멱등 | 조건부 UPDATE(`WHERE status = 'WON'` 등)로 두 번 불려도 같은 결과 | 중복 수신에 견딘다 |
| 다른 모듈 테이블을 조인하지 않는다 | lottery 쿼리는 lottery 테이블만 본다 | DB를 나눠도 쿼리가 그대로다 |

## 5. 시간 예산
`T` = `draw_at`. 처리량 = 동시 수 ÷ 응답 시간.

| 구간 | 계산 | 값 |
|---|---|---|
| 추첨 | 상한(D1) | T+10초 |
| 자동결제 첫 시도 | 2,000건 ÷ (3 ÷ 0.3초 = 초당 10건) | 약 3.3분 → T+4분 |
| 결과 미확정 최장 | REQUESTED 10초 + 재조회 10초 × 6회 → 1분 → 5분 → 15분(SA 9-4) | 약 21분 |
| 당첨 예매 선점 만료 | 3.3분 + 21분 + 여유 | 생성 + 30분 |
| 승계 마감 | D1 | T+30분 |
| 종결 최장 | 마지막 승계 예매(T+30분) + 만료 30분 | T+60분 |
| 추첨석 반환 | 종결과 함께 | 최장 T+60분 |

추첨석이 분리되어(D9) 일반 오픈 시각은 이 표에 묶이지 않는다.

자동결제 목표 "10분 안 95%"는 첫 시도 약 4분 + 재조회가 필요한 건 비율로 정해진다. 재조회 비율은 측정 전이다.

## 6. 실패 표
| 실패 | 영향 | 대응 | 대응도 실패하면 |
|---|---|---|---|
| 응모 중 DB 장애 | 응모 503 | 사용자가 같은 키로 재시도 | 마감 직전이면 응모를 놓친다. 마감 연장은 운영자 판단 |
| 마감 스케줄러 지연 | 상태만 늦게 바뀜 | 응모 INSERT 조건이 DB 시각으로 막는다 | 없음 |
| 추첨 중 서버 종료 | 트랜잭션 롤백 | 다음 주기가 다시 실행. 순위가 결정적이라 결과가 같다 | 계속 실패하면 지표 알림, 운영자 확인 |
| 추첨 시간 초과(10초) | 트랜잭션 롤백, 추첨 지연 | 다음 주기가 다시 실행 | 같은 이유로 반복되므로 연속 실패 지표 알림, B(청크)로 전환(D8) |
| 추첨 동시 실행 | — | `WHERE status='CLOSED'` 0행이면 중단 | 없음 |
| 배정 워커 중 종료 | 1건 롤백 | 남은 `WON AND reservation_id IS NULL`을 다음 주기가 집는다 | 같은 건이 계속 실패하면 FAILED(`ASSIGN_FAILED`) + 승계 |
| 배정할 좌석이 없음 | 당첨자 좌석 없음 | 추첨석을 설정 때 떼어 두고 일반 선점이 `LOTTERY`를 잡지 않아 정상 흐름에서는 생기지 않는다. 발생하면 FAILED(`NO_SEAT`) + 지표 | 운영자 보상 처리 |
| 설정 때 좌석 부족 | 추첨 설정 실패 | 조건부 UPDATE 행 수가 `seat_count`보다 적으면 트랜잭션 롤백, 409 | 운영자가 좌석 수를 줄여 다시 설정 |
| 종결 반환 누락 | 남은 추첨석이 일반 판매에 안 나옴 | 종결과 같은 트랜잭션이라 DONE이면 반환도 끝났다 | 남은 `LOTTERY` 좌석 수 지표 |
| outbox 릴레이·Kafka 정지 | 결제 요청이 안 감 | 미발행 건수 지표 알림. 복구 뒤 발행 | 선점을 1회 연장해 기다린다. 같은 예매라 늦게 도착해도 결제 멱등이 한 번만 처리한다(D12). 상한이 지나면 FAILED + 승계, 그 뒤 승인은 E8 환불 |
| PG 장애(CB OPEN) | 자동결제 대기, 랙 증가 | 오프셋을 넘기지 않고 재시도(D6). 일반 결제 자리는 분리돼 있다 | 만료까지 못 가면 결제 행 상태에 따라 재시도 또는 승계 |
| 결과 미확정(UNKNOWN → NMC) | 당첨 확정 지연 | 재조회 스케줄러(결제) | 선점 만료로 FAILED, 나중 승인은 E8 환불(D14) |
| `payment.result` 소비 실패 | 응모·예매 상태 지연 | 재시도 3회 → DLQ → 예매 쪽 DLQ 처리기 | 결제 대사 RC-2(ADR 0029)가 찾는다 |
| 동시 실패로 승계 경합 | 이중 승계·초과 배정 위험 | 실패 경로는 FAILED만 쓰고, 승계는 워커가 추첨 행 잠금으로 직렬화(D13) | 좌석 부분 유니크가 마지막 방어선 |
| 응모 뒤 빌링키 해지 | 결제 실패 | 당첨자 책임 → 승계(D4, D12) | 없음 |

## 7. 테스트 기준
| 대상 | 테스트 | 변이 테스트로 확인할 것 |
|---|---|---|
| 순위 계산 | 같은 시드·응모 → 같은 순위, 입력 순서를 섞어도 같은 순위 | 정렬 방향이나 해시 입력을 바꾸면 실패하는지 |
| 배정 규칙 | 2매 응모자가 마지막 1석에서 건너뛰어지는지, 예비자 수, 열 끝에서 ID는 연속이지만 열이 다른 두 좌석을 옆자리로 보지 않는지, 옆자리 쌍이 없으면 떨어진 두 좌석을 받는지 | 건너뛰기 대신 중단하게 바꾸거나 옆자리 판정을 ID 차이로 바꾸면 실패하는지 |
| 1인 1회차 1응모 | 같은 사용자 동시 응모 2건 → 1건만 성공(동시성 테스트), 취소 뒤 재응모 409, 취소한 응모는 추첨 대상에서 빠짐 | 유니크를 빼거나 추첨 대상의 `ENTERED` 조건을 빼면 실패하는지 |
| 추첨석 할당·반환 | 설정 → S 좌석 N개가 `LOTTERY`, 일반 선점이 `LOTTERY`를 못 잡음, 종결 → 모두 S로 | 일반 선점의 `LOTTERY` 제외 조건을 빼면 실패하는지 |
| 승계 | 실패 2건 동시 → 예비자 2명 순서대로, 좌석 수 초과 없음, 워커 2개 동시 실행에도 초과 없음, 승계 마감 뒤 승계 없음 | 추첨 행 잠금이나 마감 시각 조건을 빼면 실패하는지 |
| 재실행 | 추첨 2회 호출 → 1회만 반영 | 조건부 UPDATE 조건을 빼면 실패하는지 |
| 추첨 실행 시간 | 응모 3만 행으로 추첨 트랜잭션 시간 측정(Testcontainers), 상한 10초 | 측정이라 변이 대상 아님. 결과는 D8 바꿀 조건의 근거 |
