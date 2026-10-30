# 좌석 선점 결과 및 모듈 간 계약 (ISE-21 좌석 선점 결과 형식)

SA 17-2 U1(서비스 간 계약 4종 확정) 중 **계약 ② 좌석 선점 결과 형식(`ISE-21`)**을 좌석 선점 담당(최권우) 기준으로 정리한다. 선점 API의 요청·응답 필드, 멱등키(`Idempotency-Key`) 재요청 규칙, 에러 코드, 그리고 예매 흐름 모듈(반효승)과의 결제 토큰 발급 연동 경계를 정의한다.

상태: 제안 (2026-10-29 작성, [ADR 0032](../../decisions/0032-seat-hold-result-and-idempotency-contract.md)). 예매 흐름 담당(반효승) 확인 후 확정으로 바꾼다. 확인 요청 항목은 5절에 있다.

---

## 1. 요청 계약 (`POST /api/v1/schedules/{scheduleId}/reservations`)

> 2026-10-29 변경(입장 토큰 계약 ①, ISE-20): 경로를 `POST /api/v1/reservations`에서 바꾸고 `scheduleId`를 본문에서 경로로 옮겼다. Gateway가 본문을 읽지 않고 경로로 입장 토큰의 회차를 비교하기 위해서다.

| 구분 | 이름 | 타입 / 형식 | 필수 | 검증 규칙 |
|---|---|---|---|---|
| 헤더 | `Authorization` | `Bearer {accessToken}` | 예 | Gateway가 검증 후 `X-User-Id`, `X-User-Role` 헤더로 전달 |
| 경로 | `scheduleId` | 정수 (`Long`, 양수) | 예 | 예매할 회차 ID. 0 이하·숫자가 아니면 `400 VALIDATION_FAILED` |
| 헤더 | `X-Entry-Token` | JWS compact (JWT) | 예 | 입장 토큰(계약 ①, [`docs/design/queue/contracts.md`](../queue/contracts.md)). Gateway가 서명·`typ=entry`·`aud=gateway`·`exp`·`sub == X-User-Id`·토큰 `scheduleId` == 경로 `scheduleId`를 검증한다. 선점 서비스는 다시 검증하지 않는다 |
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

서버 설정 `seat-hold.mode=async`일 때 동작한다. 클라이언트는 응답의 `data.status`가 `HELD`(동기형 즉시 완료)인지 `REQUESTED`(접수형 폴링 대기)인지만 분기하면 된다.

- **접수 응답 (`POST /api/v1/schedules/{scheduleId}/reservations` → `202 Accepted`)**
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
  - 요청자(`X-User-Id`)가 `seat_hold_requests.user_id`와 다르면 타인 존재 여부를 숨기기 위해 `404 NOT_FOUND`를 반환한다.
  - **처리 중 (`status: "REQUESTED"`)**:
    ```json
    {
      "requestId": "c0a80101-0000-4000-8000-000000000501",
      "status": "REQUESTED",
      "nextPollAfterSeconds": 1
    }
    ```
  - **선점 성공 (`status: "HELD"`)**: `reservation` 블록에 동기형 201 응답과 같은 예매·좌석·결제 토큰 정보를 담아 반환한다.
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
| **⑤ 첫 요청이 `409`(`SEAT_ALREADY_HELD`·`PURCHASE_LIMIT_EXCEEDED`)로 실패한 뒤 같은 키로 재요청** | 첫 요청 실패 시 트랜잭션이 전체 롤백되어 `seat_hold_requests` 행이 없으므로 새로 선점을 시도한다(여전히 팔린 좌석이면 동일하게 `409`) | 소비자가 `seat_hold_requests(REJECTED)`를 커밋했으므로 `POST`는 `202 Accepted`, `GET /requests/{id}`는 `200 OK (status: REJECTED, reasonCode)` |
| **⑥ 첫 요청이 아직 처리 중인데 연타(따닥)로 동시에 두 번째 요청 도착** | 한쪽이 PK/Unique 제약 위반을 만나면 `500` 대신 롤백 후 커밋된 첫 요청 결과를 재조회해 ①~④ 규칙으로 응답 | `seat_hold_requests`가 `REQUESTED` 상태이므로 **`202 Accepted` + `Location`** 반환 |

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
| `409` | `SEAT_ALREADY_HELD` | `schedule_seats` 조건부 UPDATE 결과 갱신 행 수 < 요청 좌석 수, DB `lock_timeout(500ms)` 초과, 또는 `reservation_seats` 부분 유니크 인덱스 충돌 (E1, D2) |
| `409` | `IDEMPOTENCY_KEY_REUSED` | 이미 사용된 `Idempotency-Key`로 다른 `scheduleId`·다른 `seatIds`·다른 사용자의 요청이 들어온 경우 (E2) |
| `409` | `RESERVATION_NOT_PAYABLE` | 같은 `Idempotency-Key`로 재요청했으나 기존 선점 예매가 이미 만료(`EXPIRED`)·해제(`RELEASED`)·취소(`CANCELED`)되었거나 결제 토큰 발급 가능 시각(`holdExpiresAt - 10초`)이 지난 경우 |
| `503` | `SERVICE_UNAVAILABLE` | DB 연결 실패 등 일시적 인프라 장애 |

---

## 5. 모듈 간 연동 경계 및 확인 요청 (반효승·권영은)

### 5-1. 예매 흐름 모듈(반효승)과의 경계 — 결제 토큰 발급기
선점 모듈(`seathold`)이 예매(`HELD`)를 저장한 직후 응답에 `paymentToken`과 `paymentTokenExpiresAt`을 담으려면 예매 흐름 담당(반효승)이 구현할 **결제 토큰 발급 컴포넌트**를 호출해야 한다([ISE-22](https://linear.app/myworkspace1075/issue/ISE-22)).
- 결제 토큰 규칙([contracts.md](../payment/contracts.md) 1절 확정):
  - claim: `typ="payment"`, `aud="payment-service"`, `sub`(문자열 userId), `reservationId`(JSON 정수), `amount`(JSON 정수), `exp`(`holdExpiresAt - 10초`), `jti`(UUID)
  - 서명: HS256, `PAYMENT_TOKEN_SECRET` (`TokenDecoders.signingKey(secret)`)
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
