# 결제 서비스 간 계약 (ISE-22 결제 토큰, ISE-23 `payment.result`)

SA 17-2 U1의 계약 ③ 결제 토큰 claim과 계약 ④ `payment.result` 이벤트 필드를 결제 구현 기준으로 정리한다. 두 계약 모두 Payment 쪽은 구현이 끝났다. Reservation은 이 문서의 값에 맞춰 발급기와 소비자를 만들면 된다.

상태: 확정(2026-10-29). 반효승(Reservation)이 4절 확인 요청 1~7번을 모두 "그대로"로 확인했다(Linear ISE-22 코멘트). 이름이나 형식이 달라지면 Payment가 고칠 곳은 각 절 끝에 적었다.

## 1. 계약 ③ 결제 토큰 (Reservation 발급 → Payment 검증)

Reservation이 선점 응답에 담아 내려 주고, 사용자가 `POST /api/v1/payments` 본문의 `paymentToken`으로 보낸다. Payment는 서비스 호출 없이 토큰만으로 검증한다.

### 형식

| 항목 | 값 |
|---|---|
| 형식 | JWS compact(JWT), 서명 알고리즘 ES256(ECDSA P-256, SHA-256) 고정. 헤더는 `alg=ES256`, `typ=JWT`. 검증기는 헤더의 `alg`를 믿지 않고 ES256만 받는다([0055](../../decisions/0055-payment-token-asymmetric-signature.md)) |
| 서명 키 | 개인키 `PAYMENT_TOKEN_PRIVATE_KEY`는 Reservation만, 공개키 `PAYMENT_TOKEN_PUBLIC_KEY`는 Payment만 가진다. 입장 토큰 키와 무관하다 |
| 키 형식 | 개인키는 PKCS#8 DER, 공개키는 X.509 SubjectPublicKeyInfo DER를 줄바꿈 없는 Base64 한 줄로 둔다. 두 서비스는 시작할 때 값 존재·Base64·키 형식·P-256 곡선을 검사하고 어긋나면 기동하지 않는다 |
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
| 이벤트 | `PaymentApproved`, `PaymentDeclined`, `PaymentRefunded` |
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
| `PaymentRefunded` | `paymentId`, `reservationId`, `userId`, `amount`, `currency`, `refundedAt` | 완료(ISE-137) |

- `amount`는 소수 없는 정수 숫자(원화)다. `currency`는 항상 `"KRW"`다. SA 10-3에는 없는 필드를 변경 요청 S-18로 더했고, 필드 추가라 `version`은 그대로다.
- `approvedAt`은 Payment가 전이한 시각이 아니라 PG가 승인한 시각이다. Reservation은 이 값을 `hold_expires_at`과 비교해 만료 뒤 승인을 판단한다([0027](../../decisions/0027-reservation-payment-event-contract.md)).
- `PaymentRefunded`의 `amount`는 환불 금액이고 결제의 PG 승인 금액과 같다(전액 환불). `refundedAt`은 Payment가 전이한 시각이 아니라 PG가 환불한 시각이고 `approvedAt`처럼 밀리초 정밀도다. 환불 워커가 환불 확정과 같은 트랜잭션에서 Outbox에 쓰며, 환불이 `NEEDS_MANUAL_CHECK`로 끝난 경우에는 발행하지 않는다.
- `reasonCode`는 Payment가 정한 값이다: `CARD_DECLINED`, `INSUFFICIENT_LIMIT`, `TOKEN_EXPIRED`(PG 호출 전 토큰 만료), `NOT_SENT`(PG에 보내지 않았음이 확인됨), `OTHER`. PG 원본 코드는 내보내지 않는다. Reservation은 사유별로 분기하지 않고 거절 자체로 처리해도 된다.
- 예외: 추첨 예매(`reservations.source = 'LOTTERY'`)는 `reasonCode`로 분기한다. `NOT_SENT`면 같은 당첨자에게 새 예매로 1회 재시도하고, 나머지 사유는 예비자 승계로 보낸다([0038](../../decisions/0038-lottery-payment-failure-by-reason.md)). 재시도가 안전한 근거는 `NOT_SENT`의 보장이다. Payment는 PG 조회가 `NOT_FOUND`이고 같은 주문번호 망취소가 `Prevented`일 때만 `NOT_SENT`로 닫으므로, 그 `reservationId`로는 이후 승인이 나지 않는다(SA 10-5, `invariants.md` INV-8). 10/31 추첨 담당 선반영, 결제 담당 확인 대기(ISE-89).
- 토픽 선언(2026-11-12, ISE-236): `payment.command`는 결제 서비스가 파티션 3·복제 1로 선언한다(`PaymentCommandKafkaConfiguration`, `KafkaAdmin`이 시작할 때 만들거나 늘림). 메시지 키는 예매 ID라 발행 쪽은 파티션을 직접 지정하지 않고 키로만 보낸다. 같은 예매의 명령은 같은 파티션이라 순서가 지켜진다.
- 11/2 추가(ISE-96·89): `payment.command`의 `BillingPaymentRequested`(`reservationId`, `userId`, `amount`, `currency`, `lotteryEntryId`)는 빌링키 자동결제로 처리한다. 같은 예매로 다시 와도 결제는 한 건이다. 활성 빌링키가 없거나 PG가 빌링키를 거절하면 `PaymentDeclined.reasonCode = BILLING_KEY_MISSING`(추첨은 당첨자 책임으로 보고 승계). `pg-approve` 허가를 못 얻으면 DLQ로 보내지 않고 같은 메시지를 다시 읽는다. 그동안 같은 파티션의 뒤 메시지(환불 포함)도 기다린다(D6 기준선의 감수 사항, 측정 시나리오 2에서 확인).
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
- 구현(2026-11-03): 소비자는 ISE-73, DLQ 처리기는 ISE-74다. 이상 기록과 알림은 지표 없이 오류 로그(`event=PAYMENT_RESULT_ANOMALY`, `event=DEAD_LETTER_OPEN`)로 남긴다. 결제 결과 격리 줄은 Loki 건별 규칙 `PaymentResultAnomalyEvent`가 슬랙으로 알린다(ISE-46). 세부는 [0027](../../decisions/0027-reservation-payment-event-contract.md) 결과 절에 있다.

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
| 2 | 서명 방식과 키: ES256, 개인키는 Reservation·공개키는 Payment(ISE-245, 0055가 HS256·`PAYMENT_TOKEN_SECRET`을 대체) | 0055 |
| 3 | 토큰 `exp` = `hold_expires_at - 10초`를 발급기가 맞춘다 | 그대로 |
| 4 | `reservation.payment_id`를 확정 때 반드시 기록하고 이벤트의 `paymentId`와 대조한다 | 그대로(0027) |
| 5 | `payment.result` 소비자가 `PaymentApproved`의 `currency`·`approvedAt`을 쓴다 | 그대로 |
| 6 | 만료 뒤·확정 불가 승인에 `RefundRequested`를 `payment.command`로 발행한다(필드: `reservationId`, `paymentId`, `userId`, `amount`, `currency`, `reason`) | 그대로(M3에서 Payment가 소비) |
| 7 | 소비 실패 DLQ 처리와 이상 격리 위치는 Reservation이 정한다 | 그대로 |
| 8 | 대사용 읽기 전용 내부 조회 API 두 개(5절). 응답 형태(공통 응답으로 감쌈), 200이 아닌 응답은 모두 조회 실패, 없는 ID는 `data` 배열에서 빠짐 | 5절 그대로(#283 기준, 2026-11-12 실제 예매 서비스와 확인) |

확정되면 이 문서의 상태를 "확정"으로 바꾸고 SA의 7-3과 10-3에 claim 이름(S-15)과 `currency`(S-18)를 반영한다. 변경 요청 목록은 [sa-change-requests.md](sa-change-requests.md)에 있다.

## 5. 계약 ⑤ 대사용 예매 조회 (Payment → Reservation, #283(ISE-75))

[0029](../../decisions/0029-payment-reservation-reconciliation.md)의 대사가 부르는 Reservation의 읽기 전용 내부 API다. **아래 형태는 반효승의 PR #283(ISE-75)이고 머지됐다.** 예매 쪽은 팀 공통 응답 형식을 그대로 두고 결제 쪽 어댑터(`ReservationLookupHttpClient`)와 가짜 서버를 이 모양에 맞췄다(2026-11-03 결정, [판단 기록](../../ai-judgments/junhyun/2026-11-03-review-hyoseung-dlq-and-reconciliation-api.md)). 예매 쪽 응답이 바뀌면 다시 맞춘다. 2026-11-12에 로컬 전체 스택(compose)에서 실제 예매 서비스와 붙여 확인했고(ISE-239, [events-and-reliability.md](events-and-reliability.md) 10절 "켜기") `payment.reconciliation.reservation.enabled`를 `true`로 켰다.

| API | 응답 |
|---|---|
| `GET /internal/reconciliation/reservations?ids=1,2,3` | 공통 응답 `{"status","message","data","traceId"}`로 감싸고 `data`가 배열이다: `[{"reservationId","userId","status","totalAmount","paymentId","holdExpiresAt","updatedAt"}]`. `ids`는 1~100건. 예매 서비스에 없는 ID는 배열에서 빠진다(= 예매 없음) |
| `GET /internal/reconciliation/reservations/changes?from=&to=&cursor=` | 공통 응답으로 감싸고 `data`가 `{"items":[...], "nextCursor":<숫자>|null}`이다. `[from, to)` 안에 `CONFIRMED`·`CANCELED`로 바뀐 예매이고 항목은 현재 상태다. 쪽 크기는 서버가 고정하므로(100) `size`를 보내지 않는다. 첫 쪽은 `cursor`가 없다. 한 쪽을 꽉 채우면 마지막 예매 ID를 `nextCursor`로 주고(키셋 방식), 덜 차면 `nextCursor: null`이다. 결제 쪽은 숫자·문자열 모두 받아 그대로 다음 `cursor`로 넘긴다. 변경 목록은 RC-4의 출발점이자 결제가 7일보다 오래됐어도 예매가 최근 바뀐 경우(RC-2 등)를 결제 쪽 대상에 더하는 데 쓴다 |

- 필드: `status`는 `HELD`·`CONFIRMED`·`EXPIRED`·`RELEASED`·`CANCELED`, `totalAmount`는 원 단위 금액이다. 예매 쪽이 `BigDecimal`이라 `110000` 또는 `110000.00`으로 오고, 소수부가 0이면 정수로 받는다. 시각은 `Z`(또는 오프셋)가 붙은 ISO-8601이어야 한다(결제 쪽이 `OffsetDateTime.parse`로 읽는다). `updatedAt`은 예매 행의 마지막 갱신 시각이다. `paymentId`는 확정 전이면 `null`이다. 개인정보는 없다.
- `ids`가 비었거나 상한(100건)을 넘으면 400으로 답하고 잘라서 응답하지 않는다. 잘린 응답은 결제 쪽에서 "예매 없음"으로 보이기 때문이다.
- 인증 헤더는 없다. 내부망 신뢰이고 Gateway가 `/internal/**`를 외부에서 막는다([0008](../../decisions/0008-internal-trust-and-auth-design.md)). Eureka 서비스 ID는 `reservation-service`이고 결제 쪽은 연결 1초·읽기 2초`[가정]`로 부른다.
- **실패 해석**: 200이 아닌 응답은 404를 포함해 모두 "조회 실패"다. 시한 초과, 연결 실패, 알 수 없는 `status`, 소수부가 0이 아닌 금액, 감싼 응답에 `data`가 없는 경우도 실패다. 404를 "예매 없음"으로 읽지 않는다. 경로가 틀리거나 API가 아직 없을 때 모든 승인 결제가 RC-2(환불 의무 미등록)로 기록되기 때문이다. "예매 없음"은 200 응답의 `items`에서 빠진 ID만 뜻한다. 조회가 실패한 회차에는 RC-1~RC-6이 기록도 닫기도 하지 않고 실패하고 다음 회차에 다시 한다.
- **대량 누락**: 결제는 예매가 발급한 토큰으로만 생기므로 정상이면 "예매 없음"은 거의 없다. 200 응답이라도 한 묶음에서 2건 이상 물었는데 하나도 오지 않았거나, 회차 전체에서 빠진 예매가 5건 이상이고 물은 수의 10%를 넘으면 결제 쪽이 조회 실패로 본다(설정 `payment.reconciliation.reservation.missing-batch-min-size`·`missing-min-count`·`missing-max-ratio`, `[가정]`). 변경 목록은 빈 쪽이면 끝내고 최대 쪽 수(`max-change-pages`, 1,000`[가정]`)를 넘으면 실패로 본다.
