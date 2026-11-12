# 좌석 선점 결과 및 모듈 간 계약 (ISE-21 좌석 선점 결과 형식)

SA 17-2 U1(서비스 간 계약 4종 확정) 중 **계약 ② 좌석 선점 결과 형식(`ISE-21`)**을 좌석 선점 담당(최권우) 기준으로 정리한다. 선점 API의 요청·응답 필드, 멱등키(`Idempotency-Key`) 재요청 규칙, 에러 코드, 그리고 예매 흐름 모듈(반효승)과의 결제 토큰 발급 연동 경계를 정의한다.

상태: 확정 (2026-10-29 작성, 2026-11-01 확정, [ADR 0032](../../decisions/0032-seat-hold-result-and-idempotency-contract.md)). 5-3절 확인 요청에 예매 흐름 담당(반효승)의 답을 받지 못한 채 통합 담당이 확정했다(ISE-19·ISE-21). 결제 토큰 발급기와 선점 API는 이 문서 기본 안대로 이미 구현돼 있다. 예매 흐름 구현에서 바꿀 항목이 나오면 좌석 선점 담당과 맞춰 이 문서와 ADR 0032를 고친다.

---

## 1. 요청 계약 (`POST /api/v1/schedules/{scheduleId}/reservations`)

> 2026-10-29 변경(입장 토큰 계약 ①, ISE-20): 경로를 `POST /api/v1/reservations`에서 바꾸고 `scheduleId`를 본문에서 경로로 옮겼다. Gateway가 본문을 읽지 않고 경로로 입장 토큰의 회차를 비교하기 위해서다.

| 구분 | 이름 | 타입 / 형식 | 필수 | 검증 규칙 |
|---|---|---|---|---|
| 헤더 | `Authorization` | `Bearer {accessToken}` | 예 | Gateway가 검증 후 `X-User-Id`, `X-User-Role` 헤더로 전달 |
| 경로 | `scheduleId` | 정수 (`Long`, 양수) | 예 | 예매할 회차 ID. 0 이하·숫자가 아니면 `400 VALIDATION_FAILED` |
| 헤더 | `X-Entry-Token` | JWS compact (JWT) | 예 | 입장 토큰(계약 ①, [`docs/design/queue/contracts.md`](../queue/contracts.md)). Gateway가 서명·`typ=entry`·`aud=gateway`·`exp`·`sub == X-User-Id`·토큰 `scheduleId` == 경로 `scheduleId`를 검증한다. 선점 서비스는 다시 검증하지 않는다 |
| 헤더 | `X-Entry-Token-Id` | 문자열 (입장 토큰 `jti`, 지금 발급기는 소문자 hex 32자) | Gateway가 붙임 | Gateway가 입장 토큰 검증을 통과한 요청에만 그 토큰의 `jti`를 넣는다. 클라이언트가 보낸 같은 이름의 헤더는 모든 경로에서 지운다(`X-User-Id`와 같은 방식). 선점 서비스가 입장 토큰 소모 여부를 확인하는 데 쓴다([ADR 0053](../../decisions/0053-entry-token-consume-on-hold.md), ISE-207). 2026-11-12 Gateway 구현, 선점 서비스는 아직 읽지 않는다 |
| 헤더 | `Idempotency-Key` | UUID 문자열 | 예 | 선점 접수 ID(`requestId`)로 그대로 사용. 누락·UUID 형식 오류 시 `400 VALIDATION_FAILED` |
| 본문 | `seatIds` | JSON 정수 배열 (`List<Long>`) | 예 | 회차 좌석(`schedule_seats.id`) 목록. 1개 이상 `maxPerUser`(기본 4)개 이하, 중복 불가 |

요청 본문 예시:

```json
// POST /api/v1/schedules/31/reservations
{
  "seatIds": [1023, 1024]
}
```

- 서버는 내부 처리와 응답 생성 전에 `seatIds`를 오름차순으로 정렬한다(DB 행 잠금 교착 방지 및 멱등 본문 비교 기준 통일).
- `seatIds` 개수가 0개이거나 회차의 `max_per_user`(기본 4매)를 넘거나 배열 안에 중복된 좌석 ID가 있으면 DB 잠금을 잡기 전에 `400 SEAT_COUNT_MISMATCH`로 거절한다.

---

## 2. 성공 응답 계약

공통 성공 봉투(`{ status, message, data, traceId }`)의 `data` 필드에 아래 내용을 담는다.

### 2-1. 동기형 선점 성공 (`201 Created`, M2~M3 기본 모드)

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `requestId` | 문자열 (UUID) | 예 | 요청 헤더의 `Idempotency-Key`와 같은 값 |
| `reservationId` | JSON 정수 (`Long`) | 예 | 생성된 예매 PK (`reservations.id`) |
| `reservationNo` | 문자열 | 예 | 고객용 예매 번호 (예: `"R20261102-000501"`) |
| `scheduleId` | JSON 정수 (`Long`) | 예 | 예매한 회차 ID |
| `seatIds` | JSON 정수 배열 (`List<Long>`) | 예 | 선점된 회차 좌석 ID 목록(오름차순 정렬). 프론트엔드가 선택 좌석 표시 여부를 선택할 수 있도록 포함한다([ADR 0032](../../decisions/0032-seat-hold-result-and-idempotency-contract.md)) |
| `status` | 문자열 | 예 | `"HELD"` (멱등 재요청 시 이미 결제 확정된 예매면 `"CONFIRMED"`) |
| `holdExpiresAt` | 문자열 (ISO 8601 `+09:00`) | 예 | 선점 만료 시각 (`now + hold_minutes`, 기본 5분) |
| `totalAmount` | JSON 정수 (`Long`/`BigDecimal` 소수 0자리) | 예 | 서버가 `schedule_seats.price` 합계로 계산한 원화 정수 금액 |
| `paymentToken` | 문자열 (JWT) 또는 `null` | 조건부 | 계약 ③(`ISE-22`) 형식의 서명 결제 토큰. 유효한 `HELD` 상태(`now < holdExpiresAt - 10초`)일 때 채운다 |
| `paymentTokenExpiresAt` | 문자열 (ISO 8601 `+09:00`) 또는 `null` | 조건부 | 결제 토큰 만료 시각 (`holdExpiresAt - 10초`) |

예시(`201 Created` 응답 전체):

```json
{
  "status": 201,
  "message": "선점되었습니다",
  "data": {
    "requestId": "c0a80101-0000-4000-8000-000000000501",
    "reservationId": 501,
    "reservationNo": "R20261102-000501",
    "scheduleId": 31,
    "seatIds": [1023, 1024],
    "status": "HELD",
    "holdExpiresAt": "2026-11-02T20:05:03+09:00",
    "totalAmount": 330000,
    "paymentToken": "eyJhbGciOiJIUzI1NiJ9...",
    "paymentTokenExpiresAt": "2026-11-02T20:04:53+09:00"
  },
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

### 2-2. 접수형 선점 응답 (`202 Accepted` 및 결과 조회, M4 고도화 모드)

서버 설정 `seat-hold.mode=async`일 때 동작한다. 모드는 reservation-service가 기동할 때 한 번 읽고, 바꾸려면 재시작한다(`sync` 기본, [ADR 0057](../../decisions/0057-async-seat-hold-intake-and-mode.md)). 클라이언트는 응답의 `data.status`가 `HELD`(동기형 즉시 완료)인지 `REQUESTED`(접수형 폴링 대기)인지만 분기하면 된다.

> 2026-11-12 변경(ISE-16, ADR 0056·0057):
> - 접수형 POST도 접수 단계에서 동기형과 같은 입력 검증을 한다. 요청 좌석이 이미 잡혀 있으면 행을 남기지 않고 즉시 `409 SEAT_ALREADY_HELD`를 준다. 잠금 없는 읽기이고 최종 판단은 소비자가 한다. 이 항목은 ISE-120 담당 확인 뒤 확정한다.
> - 결과 조회 API는 모드와 상관없이 열려 있다.
> - 메시지 키는 `{scheduleId}:{section}`이고, 좌석이 여러 구역에 걸치면 사전순으로 가장 작은 구역을 쓴다.

- **접수 단계 검사 (`202` 전에 끝나는 것)**
  - 동기형과 같은 `400`·`404`·`409 SALE_NOT_OPEN` 검사(1절, 4절)
  - 요청 좌석 중 하나라도 `AVAILABLE`이 아니거나 추첨석이면 `409 SEAT_ALREADY_HELD`. `seat_hold_requests` 행을 남기지 않으므로 같은 키로 다시 보내면 새로 판단한다
  - 매수 한도(`PURCHASE_LIMIT_EXCEEDED`)는 접수에서 보지 않는다. 앞선 접수 요청의 매수를 반영해야 해서 소비자가 판단하고 조회 결과의 `REJECTED`로 알린다
- **접수 응답 (`POST /api/v1/schedules/{scheduleId}/reservations` → `202 Accepted`)**
  - 같은 `Idempotency-Key` 재요청도 기존 행의 상태와 상관없이 같은 `202`와 같은 본문을 준다. `data.status: "REQUESTED"`는 "결과는 `Location`에서 확인하라"는 뜻이다. 실제 결과(`HELD`·`REJECTED`)는 조회 API로만 알린다
  - 헤더: `Location: /api/v1/reservations/requests/{requestId}`
  - `data`:
    ```json
    {
      "requestId": "c0a80101-0000-4000-8000-000000000501",
      "status": "REQUESTED",
      "nextPollAfterSeconds": 1
    }
    ```
- **접수 결과 조회 (`GET /api/v1/reservations/requests/{requestId}` → `200 OK`)**
  - 모드와 상관없이 동작한다. 동기형은 성공한 선점만 행을 남기므로 `HELD`나 `404`만 나온다.
  - 입장 토큰(`X-Entry-Token`)은 필요 없고 로그인만 필요하다. `requestId`가 UUID 형식이 아니면 `400 VALIDATION_FAILED`다.
  - 요청자(`X-User-Id`)가 `seat_hold_requests.user_id`와 다르면 타인 존재 여부를 숨기기 위해 `404 NOT_FOUND`를 반환한다.
  - **처리 중 (`status: "REQUESTED"`)**:
    ```json
    {
      "requestId": "c0a80101-0000-4000-8000-000000000501",
      "status": "REQUESTED",
      "nextPollAfterSeconds": 1
    }
    ```
  - **선점 성공 (`status: "HELD"`)**: `reservation` 블록에 동기형 201 응답과 같은 예매·좌석·결제 토큰 정보를 담아 반환한다. `reservation.status`는 예매의 현재 상태다. 결제 토큰은 예매가 `HELD`이고 `now < holdExpiresAt - 10초`일 때만 새로 서명해 넣고, 그 밖(`CONFIRMED`, 만료·해제·취소, 결제 가능 시각 지남)에는 `paymentToken`과 `paymentTokenExpiresAt`이 `null`이다.
    ```json
    {
      "requestId": "c0a80101-0000-4000-8000-000000000501",
      "status": "HELD",
      "reservation": {
        "reservationId": 501,
        "reservationNo": "R20261102-000501",
        "scheduleId": 31,
        "seatIds": [1023, 1024],
        "status": "HELD",
        "holdExpiresAt": "2026-11-02T20:05:03+09:00",
        "totalAmount": 330000,
        "paymentToken": "eyJhbGciOiJIUzI1NiJ9...",
        "paymentTokenExpiresAt": "2026-11-02T20:04:53+09:00"
      }
    }
    ```
  - **선점 거절 (`status: "REJECTED"`)**:
    ```json
    {
      "requestId": "c0a80101-0000-4000-8000-000000000501",
      "status": "REJECTED",
      "reasonCode": "SEAT_ALREADY_HELD"
    }
    ```
    (`reasonCode` 값: `SEAT_ALREADY_HELD`, `PURCHASE_LIMIT_EXCEEDED`, `PROCESSING_FAILED`, `TIMEOUT`)

---

## 3. 멱등키(`Idempotency-Key`) 재요청 처리 규칙

선점 API는 `seat_hold_requests` 테이블의 PK(`request_id` = `Idempotency-Key`)로 멱등성을 보장한다([ADR 0032](../../decisions/0032-seat-hold-result-and-idempotency-contract.md)).

### 3-1. 본문 일치 판정 기준
기존 `seat_hold_requests` 행이 존재할 때 아래 3가지가 모두 같아야 "같은 요청의 재시도"로 인정한다. 하나라도 다르면 **`409 IDEMPOTENCY_KEY_REUSED`**를 반환한다.
1. `seat_hold_requests.user_id == X-User-Id` (다른 사용자의 키 충돌·도용 차단)
2. `seat_hold_requests.schedule_id == 경로 scheduleId`
3. `seat_hold_requests.seat_ids == sort(request.seatIds)` (오름차순 정렬된 좌석 ID 배열 일치)

### 3-2. 상황별 재요청 응답 (동기형·접수형)

| 상황 | 동기형 (`seat-hold.mode=sync`) | 접수형 (`seat-hold.mode=async`) |
|---|---|---|
| **① 같은 키 + 다른 본문(또는 다른 사용자)** | `409 IDEMPOTENCY_KEY_REUSED` | `409 IDEMPOTENCY_KEY_REUSED` |
| **② 첫 요청이 `HELD`로 성공했고 예매가 아직 `HELD`(`now < holdExpiresAt - 10초`)** | 좌석 잠금 재시도 없이 기존 예매와 새로 서명(또는 기존 만료 시각 기준)한 결제 토큰으로 **`201 Created`** 반환 | `POST` 재요청은 **`202 Accepted` + `Location`**, `GET /requests/{id}`는 `200 OK (status: HELD)` |
| **③ 첫 요청이 `HELD`로 성공했고 그사이 결제까지 완료(`CONFIRMED`)됨** | **`201 Created`** (`status: "CONFIRMED"`, `paymentToken: null`, `paymentTokenExpiresAt: null`) | `POST`는 `202 Accepted`, `GET /requests/{id}`는 `200 OK` |
| **④ 첫 요청이 `HELD`로 성공했으나 재요청 시점에 이미 만료·해제·취소(`EXPIRED`·`RELEASED`·`CANCELED`)됨, 또는 `now >= holdExpiresAt - 10초`** | 더 이상 그 선점으로 결제할 수 없으므로 **`409 RESERVATION_NOT_PAYABLE`** (새 좌석 선택·새 멱등키 유도) | `POST`는 `202 Accepted`, `GET /requests/{id}`는 예매의 현재 상태 반영 |
| **⑤ 첫 요청이 `409`(`SEAT_ALREADY_HELD`·`PURCHASE_LIMIT_EXCEEDED`)로 실패한 뒤 같은 키로 재요청** | 첫 요청 실패 시 트랜잭션이 전체 롤백되어 `seat_hold_requests` 행이 없으므로 새로 선점을 시도한다(여전히 팔린 좌석이면 동일하게 `409`) | 접수 단계에서 즉시 `409`로 거절됐으면 행이 없으므로 새로 판단한다(동기형과 같음). 소비자가 `seat_hold_requests(REJECTED)`를 커밋했으면 `POST`는 `202 Accepted`, `GET /requests/{id}`는 `200 OK (status: REJECTED, reasonCode)` |
| **⑥ 첫 요청이 아직 처리 중인데 연타(따닥)로 동시에 두 번째 요청 도착** | 한쪽이 PK/Unique 제약 위반을 만나면 `500` 대신 롤백 후 커밋된 첫 요청 결과를 재조회해 ①~④ 규칙으로 응답. M2 기준선(ISE-12)은 사전 조회만 해서 두 번째 요청이 좌석 행 잠금을 기다린 뒤 `409 SEAT_ALREADY_HELD`를 받을 것으로 본다(구조상 추정, 미재현). M4 TS-3에서 재현 후 고친다([ADR 0035](../../decisions/0035-defer-seathold-concurrency-and-reconciliation-to-m4.md)) | `seat_hold_requests`가 `REQUESTED` 상태이므로 **`202 Accepted` + `Location`** 반환 |
| **⑦ 첫 요청을 접수형 서버가 받았고(`REQUESTED`·`REJECTED`), 그 뒤 서버가 동기형으로 재시작됨** | 실패로 답하지 않고 **`202 Accepted` + `Location`**(접수형 202와 같은 본문). 결과는 `GET /requests/{id}`로 본다. 요청은 시작한 방식으로 끝까지 답한다([ADR 0058](../../decisions/0058-sync-replay-of-async-requests.md)). 행이 `HELD`가 됐으면 ②~④를 따른다 | ①~⑤와 같다 |

---

## 4. 실패 응답 및 에러 코드 목록

공통 실패 봉투(`{ status, code, message, errors, traceId }`)를 따른다. `409`는 모두 정상 비즈니스 거절로 집계하며 서버 오류율(5xx)에 넣지 않는다(SA 3장).

| HTTP 상태 | 에러 코드 (`code`) | 발생 조건 |
|---|---|---|
| `400` | `VALIDATION_FAILED` | `Idempotency-Key` 헤더 누락·UUID 형식 오류, 경로 `scheduleId` 비양수·숫자 아님, 요청 JSON 파싱 오류 |
| `400` | `SEAT_COUNT_MISMATCH` | `seatIds`가 비어 있거나 회차의 `max_per_user`(기본 4매)를 초과하거나, 배열 안에 중복된 `seatId`가 있거나, 요청한 `seatId`가 해당 `scheduleId`에 속하지 않는 경우 |
| `401` | `UNAUTHORIZED` / `TOKEN_EXPIRED` / `TOKEN_REVOKED` | Access JWT 누락·만료·로그아웃 (Gateway 또는 `GatewayIdentityFilter`) |
| `403` | `ENTRY_TOKEN_INVALID` | `X-Entry-Token` 누락·서명 위조·만료·`typ`/`aud` 불일치·`sub != X-User-Id`·토큰 `scheduleId` != 경로 `scheduleId` (E5, E14). Gateway가 응답한다 |
| `404` | `NOT_FOUND` | 존재하지 않는 `scheduleId`로 선점 요청, 또는 접수 결과 조회 시 존재하지 않거나 타인의 `requestId` 조회 |
| `409` | `SALE_NOT_OPEN` | 서버 시각(`now`)이 회차의 `open_at` 이전이거나 이미 판매 종료된 회차 (E4) |
| `409` | `PURCHASE_LIMIT_EXCEEDED` | `user_schedule_quotas` 조건부 UPDATE 결과 0행 (`held_count + 요청 매수 > max_per_user`) (E3, D1) |
| `409` | `SEAT_ALREADY_HELD` | `schedule_seats` 조건부 UPDATE 결과 갱신 행 수 < 요청 좌석 수, DB `lock_timeout(500ms)` 초과, 또는 `reservation_seats` 부분 유니크 인덱스 충돌 (E1, D2). 접수형은 접수 단계에서 요청 좌석이 `AVAILABLE`이 아니거나 추첨석으로 보일 때도 즉시 반환한다(ADR 0057) |
| `409` | `IDEMPOTENCY_KEY_REUSED` | 이미 사용된 `Idempotency-Key`로 다른 `scheduleId`·다른 `seatIds`·다른 사용자의 요청이 들어온 경우 (E2) |
| `409` | `RESERVATION_NOT_PAYABLE` | 같은 `Idempotency-Key`로 재요청했으나 기존 선점 예매가 이미 만료(`EXPIRED`)·해제(`RELEASED`)·취소(`CANCELED`)되었거나 결제 토큰 발급 가능 시각(`holdExpiresAt - 10초`)이 지난 경우 |
| `503` | `SERVICE_UNAVAILABLE` | DB 연결 실패 등 일시적 인프라 장애 |

---

## 5. 모듈 간 연동 경계 및 확인 요청 (반효승·권영은)

### 5-1. 예매 흐름 모듈(반효승)과의 경계 — 결제 토큰 발급기
선점 모듈(`seathold`)이 예매(`HELD`)를 저장한 직후 응답에 `paymentToken`과 `paymentTokenExpiresAt`을 담으려면 예매 흐름 담당(반효승)이 구현할 **결제 토큰 발급 컴포넌트**를 호출해야 한다([ISE-22](https://linear.app/myworkspace1075/issue/ISE-22)).
- 결제 토큰 규칙([contracts.md](../payment/contracts.md) 1절 확정):
  - claim: `typ="payment"`, `aud="payment-service"`, `sub`(문자열 userId), `reservationId`(JSON 정수), `amount`(JSON 정수), `exp`(`holdExpiresAt - 10초`), `jti`(UUID)
  - 서명: ES256, 개인키 `PAYMENT_TOKEN_PRIVATE_KEY`(PKCS#8 DER의 Base64 한 줄, ISE-245·ADR 0055). 결제 서비스는 공개키로 검증한다
- **인터페이스 제안**:
  ```java
  public interface PaymentTokenIssuer {
      IssuedPaymentToken issue(Long userId, Long reservationId, long amount, Instant holdExpiresAt);

      record IssuedPaymentToken(String token, Instant expiresAt) {}
  }
  ```

### 5-2. 대기열 모듈·Gateway(권영은)와의 경계 — 입장 토큰 회차 검증 (`ISE-20`)
- 2026-10-29 정함: 선점 경로에 `scheduleId`를 둔다(`POST /api/v1/schedules/{scheduleId}/reservations`).
- Gateway가 입장 토큰의 서명·`typ=entry`·`aud=gateway`·`exp`·`sub == X-User-Id`와 토큰 `scheduleId` == 경로 `scheduleId`를 모두 검증하고, 틀리면 `403 ENTRY_TOKEN_INVALID`를 응답한다. Gateway는 요청 본문을 읽지 않는다.
- 선점 서비스는 입장 토큰을 다시 검증하지 않는다. `seatIds`가 경로 회차의 좌석인지는 선점 서비스가 확인한다(`400 SEAT_COUNT_MISMATCH`).
- 입장 토큰 형식은 [`docs/design/queue/contracts.md`](../queue/contracts.md)를 따른다.

### 5-3. 확인 요청 항목 (반효승)
각 항목에 "그대로 / 이렇게 바꾼다"로 확인해 주면 상태를 `확정`으로 바꾼다.

| # | 항목 | 기본 안 |
|---|---|---|
| 1 | 동기형 201 응답 및 접수형 `reservation` 블록 필드: `requestId`, `reservationId`, `reservationNo`, `scheduleId`, `seatIds`, `status`, `holdExpiresAt`, `totalAmount`, `paymentToken`, `paymentTokenExpiresAt` | 2절 표 그대로 (`scheduleId`·`seatIds` 포함) |
| 2 | 동기형 실패(409) 시 `seat_hold_requests` 행도 함께 롤백하고, 성공(`HELD`) 시에만 커밋해 멱등 재요청에 응답 | 3절 표 그대로 ([ADR 0032](../../decisions/0032-seat-hold-result-and-idempotency-contract.md)) |
| 3 | 이미 만료·해제·취소됐거나 `holdExpiresAt - 10초`가 지난 선점에 같은 멱등키로 재요청 시 `409 RESERVATION_NOT_PAYABLE` 반환 | 3절 표 그대로 |
| 4 | 결제 토큰 발급기(`PaymentTokenIssuer`) 시그니처와 패키지 위치(`reservation` 도메인/응용 또는 공유 포트) | 5-1절 제안 그대로 |

---

## 6. 예매 취소 (`POST /api/v1/reservations/{reservationId}/cancel`, ISE-206)

확정(`CONFIRMED`)된 본인의 일반 예매를 공연 시작 전까지 취소한다. 취소하면 좌석을 돌려주고 티켓을 무효로 만들고, 결제 서비스에 환불을 요청한다. 환불 요청은 결제 서비스가 이미 처리하는 `RefundRequested(reason=CANCELED)`이므로 이번 계약은 예매 서비스 안에서 끝난다.

### 6-1. 요청과 응답
- 경로 `reservationId`는 1 이상의 정수다. 로그인한 사용자 ID는 Gateway가 넘긴 `X-User-Id`만 쓴다.
- `Idempotency-Key` 헤더는 필수이고 UUID 표준 형식(8-4-4-4-12)이어야 한다. 요청 본문은 없다.
- 성공은 `202 Accepted`이고 `data`는 다음과 같다. 환불은 비동기로 처리되므로 환불 상태는 예매 상세(`GET /api/v1/reservations/{reservationId}`)의 `paymentId`로 결제 조회 API를 불러 본다(2026-11-11 사용자 결정, ISE-206).

| 필드 | 타입 | 설명 |
|---|---|---|
| `reservationId` | number | 취소한 예매 ID |
| `status` | string | 항상 `CANCELED` |
| `paymentId` | number | 환불 대상 결제 ID |

### 6-2. 에러 코드

| 상태 | 코드 | 발생 조건 |
|---|---|---|
| `404` | `NOT_FOUND` | 없는 예매이거나 다른 사용자의 예매. 예매 상세 조회와 같은 기준으로 둘을 구분하지 않는다 |
| `400` | `VALIDATION_FAILED` | `Idempotency-Key`가 없거나 UUID 형식이 아님, `reservationId`가 0 이하 |
| `409` | `RESERVATION_NOT_CANCELABLE` | 상태가 `HELD`·`EXPIRED`·`RELEASED`, 회차가 없음, 또는 취소하는 사이에 상태가 바뀜 |
| `409` | `LOTTERY_RESERVATION_NOT_CANCELABLE` | 추첨(`source=LOTTERY`)으로 배정된 예매 |
| `409` | `CANCEL_PERIOD_ENDED` | 지금 시각이 회차 `startsAt`과 같거나 지남 |

### 6-3. 취소 조건과 검사 순서
취소할 수 있는 예매는 `status=CONFIRMED`, `source=GENERAL`이고 지금 시각이 회차 `startsAt`보다 이른 예매다(2026-11-11 사용자 결정, ISE-206). 검사는 다음 순서로 한다.

1. 본인의 예매가 아니면 `404`.
2. 이미 `CANCELED`이면 아무것도 쓰지 않고 `202`를 다시 돌려준다. 기한이 지났어도 같다.
3. 추첨 예매이면 `LOTTERY_RESERVATION_NOT_CANCELABLE`.
4. 상태가 `CONFIRMED`가 아니면 `RESERVATION_NOT_CANCELABLE`.
5. 회차 시작 시각이 지났으면 `CANCEL_PERIOD_ENDED`.

### 6-4. 멱등 규칙
`Idempotency-Key`는 형식만 확인하고 저장하지 않는다. 취소는 예매마다 한 번만 일어나는 전이라 예매 상태가 곧 멱등 기록이기 때문이다(설계 판단이며 ISE-206 댓글로 공유했다). 같은 예매에 키가 같든 다르든 다시 요청하면 둘째부터는 아무것도 쓰지 않고 같은 `202`를 돌려주므로 환불 요청도 한 건만 나간다. 동시에 두 요청이 들어와도 조건부 UPDATE(`WHERE status='CONFIRMED'`)가 한쪽만 통과시키고, 진 쪽은 상태를 다시 읽어 `CANCELED`이면 `202`로 답한다.

### 6-5. 한 트랜잭션에서 바뀌는 것
다음 순서로 쓰고 순서를 바꾸지 않는다.

1. 예매 `CONFIRMED → CANCELED`. `updated_at`을 갱신한다. 결제 서비스 대사(ADR 0029)가 `updated_at`으로 취소를 찾는다.
2. 사용자·회차별 보유 매수(`user_schedule_quotas.held_count`)를 예매 수량만큼 뺀다.
3. 예매 좌석 `CONFIRMED → RELEASED`. 부분 유니크 인덱스에서 빠져 같은 좌석을 다시 선점할 수 있다.
4. 회차 좌석 `SOLD → AVAILABLE`. 같은 예매의 취소끼리는 1번의 예매 행 잠금에서 줄을 서고, 좌석 선점은 `AVAILABLE` 좌석만 잠가 `SOLD` 좌석과 잠금 순서가 엇갈리지 않는다.
5. 티켓 `ISSUED → VOID`.
6. 상태 이력 `CONFIRMED → CANCELED`(사유 `USER_CANCELED`).
7. outbox에 `RefundRequested` 한 건을 쓴다.

매수 행을 좌석보다 먼저 잠그는 이유는 좌석 선점이 매수 행 → 회차 좌석 순서로 잠그기 때문이다. 같은 사용자가 같은 회차에서 선점과 취소를 동시에 할 때 순서가 반대면 교착이 날 수 있다.

### 6-6. RefundRequested(CANCELED)
토픽은 `payment.command`, 이벤트 타입은 `RefundRequested`, 버전은 1, 메시지 키는 `reservationId`다. 필드는 SA 10-3과 같고 값은 예매 원장에서 가져온다.

| 필드 | 값 |
|---|---|
| `reservationId` | 취소한 예매 ID |
| `paymentId` | 예매의 `payment_id` |
| `userId` | 예매한 사용자 ID |
| `amount` | 예매 총액(`total_amount`) |
| `currency` | `KRW` |
| `reason` | `CANCELED` |

### 6-7. 범위 밖
- 추첨 예매의 취소: 추첨 예매의 좌석은 추첨석(`LOTTERY` 등급)이라, 진행 중인 추첨에서 풀리면 승계 배정과 종결 때의 등급 되돌리기와 얽힌다. 그래서 이번 범위에서 뺐다(2026-11-11 사용자 결정, ISE-206). 필요해지면 추첨 담당과 따로 정한다.
- 부분 취소(좌석 일부만 취소)와 취소 수수료는 다루지 않는다. 취소는 예매 전체를 환불한다.
