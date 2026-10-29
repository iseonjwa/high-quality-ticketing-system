# 결제 서비스 간 계약 (ISE-22 결제 토큰, ISE-23 `payment.result`)

SA 17-2 U1의 계약 ③ 결제 토큰 claim과 계약 ④ `payment.result` 이벤트 필드를 결제 구현 기준으로 정리한다. 두 계약 모두 Payment 쪽은 구현이 끝났다. Reservation은 이 문서의 값에 맞춰 발급기와 소비자를 만들면 된다.

상태: 확정 (2026-10-29 반효승 확인 완료, ISE-22·ISE-23). 확인된 항목은 4절에 있다. 이름이나 형식이 달라지면 Payment가 고칠 곳은 각 절 끝에 적었다.

## 1. 계약 ③ 결제 토큰 (Reservation 발급 → Payment 검증)

Reservation이 선점 응답에 담아 내려 주고, 사용자가 `POST /api/v1/payments` 본문의 `paymentToken`으로 보낸다. Payment는 서비스 호출 없이 토큰만으로 검증한다.

### 형식

| 항목 | 값 |
|---|---|
| 형식 | JWS compact(JWT), 서명 알고리즘 HS256 고정. 헤더의 `alg`를 믿지 않는다 |
| 서명 키 | `PAYMENT_TOKEN_SECRET`. 입장 토큰 키와 분리하며 Reservation·Payment만 가진다 |
| 키 바이트 | 문자열의 UTF-8 바이트를 그대로 쓴다(16진수 디코딩 금지). 발급기도 `TokenDecoders.signingKey(secret)`를 쓴다([0025](../../decisions/0025-payment-token-secret-injection.md)) |
| 길이 상한 | 4,096자. 넘으면 검증 전에 거절한다 |

### claim

| claim | 타입 | 필수 | 의미와 검증 |
|---|---|---|---|
| `typ` | 문자열 | 예 | 값 `payment`. 다르면 거절 |
| `aud` | 문자열 또는 배열 | 예 | `payment-service`를 포함해야 한다 |
| `sub` | 문자열 | 예 | 사용자 ID(양의 정수 문자열). `X-User-Id`와 같아야 한다 |
| `reservationId` | JSON 정수 | 예 | 예매 ID. 문자열이나 소수는 거절한다 |
| `amount` | JSON 정수 | 예 | 원화 정수 금액(양수). 소수가 섞이면 거절한다. 요청 본문의 `amount`와 같아야 한다 |
| `exp` | NumericDate(초) | 예 | 만료 시각. SA 7-3의 값은 `hold_expires_at - 10초` |
| `jti` | 문자열 | 예 | 추적·로그용. 폐기 목록은 만들지 않는다 |
| `iat` | NumericDate | 아니오 | 있어도 검증하지 않는다 |

예시(payload):

```json
{ "typ": "payment", "aud": "payment-service", "sub": "42", "reservationId": 8100001,
  "amount": 50000, "exp": 1793176530, "jti": "b1e0c5c2-2f1a-4c4e-8a56-0d7d3c1f9a10" }
```

### Payment의 동작

- 서명·`typ`·`aud`·필수 claim 중 하나라도 틀리면 403 `PAYMENT_TOKEN_INVALID`다. 이유는 응답에 담지 않는다.
- `sub`가 `X-User-Id`와 다르면 403이다. `amount`가 요청 금액과 다르면 400 `PAYMENT_AMOUNT_MISMATCH`다. 둘 다 PG 호출 전에 막힌다.
- `exp`가 지나면 신규 승인은 거절한다. 만료 뒤 24시간까지는 같은 사용자의 기존 결제 결과를 같은 키 재요청으로 받을 수 있다([0019](../../decisions/0019-payment-new-key-existing-payment.md)). 결제 조회 `GET /api/v1/payments/{paymentId}`는 토큰이 필요 없다.
- 예매 ID·금액이 서버 값과 맞는지는 이 토큰만으로 알 수 없다. Reservation이 발급 때 원장 값을 넣고, 소비 때 `PaymentApproved`의 값을 원장과 다시 대조한다([0027](../../decisions/0027-reservation-payment-event-contract.md)).
- Payment가 `exp`와 선점 만료의 관계는 확인하지 않는다. 만료를 `hold_expires_at - 10초`로 맞추는 책임은 발급기에 있다.

Payment 쪽 고칠 곳: claim 이름은 `payment-service`의 `PaymentTokenClaims`, 검증 규칙은 `JwtPaymentTokenVerifier`다.

## 2. 계약 ④ `payment.result` (Payment 발행 → Reservation 소비)

| 항목 | 값 |
|---|---|
| 토픽 | `payment.result` |
| 이벤트 | `PaymentApproved`, `PaymentDeclined`, `PaymentRefunded`(환불은 M3) |
| 파티션 키 | `reservationId`(문자열). 같은 예매의 결제 이벤트는 순서가 유지된다 |
| 발행 방식 | Outbox. 결제 상태 전이와 같은 트랜잭션에 쓰고 릴레이가 Kafka로 보낸다 |
| 소비자 그룹 | `reservation.payment-result` |
| 중복 판단 기준 | 봉투의 `eventId`. 소비자는 `processed_events(event_id, consumer_group)`로 거른다 |
| 형식 변경 | 필드 추가만 허용하고 `version`은 1로 유지한다. 의미가 바뀌면 `version`을 올린다 |

### 공통 봉투

```json
{
  "eventId": "7f3c2a9e-1b4d-4c8e-9a51-3e0f6b2d9c11",
  "eventType": "PaymentApproved",
  "version": 1,
  "occurredAt": "2026-11-02T11:00:01.123Z",
  "producer": "payment-service",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "payload": { }
}
```

`occurredAt`은 Outbox에 쓴 시각이고 밀리초 정밀도다.

### 페이로드

| 이벤트 | 필드 | 구현 |
|---|---|---|
| `PaymentApproved` | `paymentId`, `reservationId`, `userId`, `amount`, `currency`, `approvedAt` | 완료 |
| `PaymentDeclined` | `paymentId`, `reservationId`, `userId`, `reasonCode` | 완료 |
| `PaymentRefunded` | `paymentId`, `reservationId`, `userId`, `amount`, `currency`, `refundedAt` | M3 |

- `amount`는 소수 없는 정수 숫자(원화)다. `currency`는 항상 `"KRW"`다. SA 10-3에는 없는 필드를 변경 요청 S-18로 더했고, 필드 추가라 `version`은 그대로다.
- `approvedAt`은 Payment가 전이한 시각이 아니라 PG가 승인한 시각이다. Reservation은 이 값을 `hold_expires_at`과 비교해 만료 뒤 승인을 판단한다([0027](../../decisions/0027-reservation-payment-event-contract.md)).
- `reasonCode`는 Payment가 정한 값이다: `CARD_DECLINED`, `INSUFFICIENT_LIMIT`, `TOKEN_EXPIRED`(PG 호출 전 토큰 만료), `NOT_SENT`(PG에 보내지 않았음이 확인됨), `OTHER`. PG 원본 코드는 내보내지 않는다. Reservation은 사유별로 분기하지 않고 거절 자체로 처리해도 된다.
- 예시(`PaymentApproved` payload):

```json
{ "paymentId": 17, "reservationId": 8100001, "userId": 42, "amount": 50000, "currency": "KRW",
  "approvedAt": "2026-11-02T11:00:01.100Z" }
```

### Reservation이 지켜 줄 것

- 이벤트의 `reservationId`·`userId`·`amount`·`currency`를 예매 원장과 대조하고 예매 상태별로 처리한다. 상태 표는 [0027](../../decisions/0027-reservation-payment-event-contract.md)에 있다. 특히 `CONFIRMED` 예매에 같은 `paymentId`가 다시 오면 변경 없이 넘기고, `PaymentApproved`가 `HELD`가 아닌 예매에 오면 `RefundRequested`를 `payment.command`로 발행한다.
- 같은 예매에 결제 행은 하나다([0026](../../decisions/0026-payment-one-per-reservation.md)). 거절·환불된 예매는 새로 선점해야 다시 결제할 수 있고, 같은 `reservationId`로 새 결제를 시도하면 409 `PAYMENT_ALREADY_CLOSED`다.
- 승인 API의 200 `APPROVED`는 예매 확정이 아니다. 확정은 Reservation이 `PaymentApproved`를 소비한 뒤에 일어난다. 클라이언트는 예매 조회로 확정을 확인한다.
- 소비 실패가 3회 재시도 뒤 DLQ로 가면 Reservation의 `payment.result` DLQ 처리기가 이어받는다. 못 처리한 건은 Payment 대사(RC-2)가 찾는다([0029](../../decisions/0029-payment-reservation-reconciliation.md)).

Payment 쪽 고칠 곳: 필드는 `PaymentApprovedEvent`·`PaymentDeclinedEvent`, 발행은 `PaymentResultPublisher`다.

## 3. 결제 API 요약 (Reservation이 알아 둘 동작)

전체 명세는 [api.md](api.md)에 있다.

| API | 응답 | 비고 |
|---|---|---|
| `POST /api/v1/payments` | 200 `APPROVED`·`DECLINED`, 202 `REQUESTED`·`UNKNOWN`, 403·400·409·503 | 헤더 `Idempotency-Key`(UUID) 필수 |
| `GET /api/v1/payments/{paymentId}` | 200, 타인·없음 404 | 소유자 또는 ADMIN만. 토큰 불필요 |

202와 `UNKNOWN`은 PG 결과를 아직 모르는 상태다. 결제 서비스가 재확인해 확정하고 결과는 `payment.result`로 나온다. 클라이언트와 Reservation은 이 상태를 실패로 보면 안 된다.

## 4. 확인 요청 (반효승)

각 항목에 "그대로 / 이렇게 바꾼다"로 답해 주면 된다.

| # | 항목 | 기본 가정 |
|---|---|---|
| 1 | claim 이름·타입: `sub`, `reservationId`, `amount`, `typ`, `aud`, `exp`, `jti` | 1절 표 그대로(`reservationId`·`amount`는 JSON 정수) |
| 2 | 서명 방식과 키: HS256, `PAYMENT_TOKEN_SECRET`, `TokenDecoders.signingKey` | 그대로 |
| 3 | 토큰 `exp` = `hold_expires_at - 10초`를 발급기가 맞춘다 | 그대로 |
| 4 | `reservation.payment_id`를 확정 때 반드시 기록하고 이벤트의 `paymentId`와 대조한다 | 그대로(0027) |
| 5 | `payment.result` 소비자가 `PaymentApproved`의 `currency`·`approvedAt`을 쓴다 | 그대로 |
| 6 | 만료 뒤·확정 불가 승인에 `RefundRequested`를 `payment.command`로 발행한다(필드: `reservationId`, `paymentId`, `userId`, `amount`, `currency`, `reason`) | 그대로(M3에서 Payment가 소비) |
| 7 | 소비 실패 DLQ 처리와 이상 격리 위치는 Reservation이 정한다 | 그대로 |

확정되면 이 문서의 상태를 "확정"으로 바꾸고 SA의 7-3과 10-3에 claim 이름(S-15)과 `currency`(S-18)를 반영한다. 변경 요청 목록은 [sa-change-requests.md](sa-change-requests.md)에 있다.
