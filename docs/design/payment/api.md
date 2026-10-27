# 결제 API

승인과 조회 두 API를 둔다. 운영자 복구는 HTTP API가 아니라 Ops 명령이다([0027](../../decisions/0027-payment-ops-commands.md)). 빌링키 등록(`POST /api/v1/payments/billing-keys`)은 M4에서 설계한다. 응답은 `SuccessResponse`·`FailureResponse` 공통 형식을 따르고(SA 11-2), 아래 `data`는 그 안의 내용이다.

## 1. 결제 승인 `POST /api/v1/payments`

헤더: `Idempotency-Key`(UUID, 필수). 본문: `{paymentToken, amount, method: "CARD"}`. 인증이 필요하다. M4 토스 어댑터는 `clientPaymentRef`를 더 받는다([SA 변경 요청](sa-change-requests.md) S-14).

### 처리 순서

| # | 단계 | 실패 또는 분기 | 근거 |
|---|---|---|---|
| 1 | 인증 확인. `X-User-Id`는 Gateway가 검증한 값만 쓴다 | 401 | [0020](../../decisions/0020-payment-object-level-authorization.md) |
| 2 | 형식 검증: 키는 UUID, `method`는 `CARD`만(`BILLING`은 M4 자동결제 이벤트 전용), `amount`는 양의 정수, `paymentToken`은 4,096자 이하`[가정]` | 400 `VALIDATION_FAILED`. 토큰 길이 초과는 403 `PAYMENT_TOKEN_INVALID` | [0024](../../decisions/0024-payment-token-secret-injection.md) |
| 3 | `(user_id, Idempotency-Key)` 멱등 기록 조회. 토큰 검증보다 먼저, 허가 없이 한다 | 해시가 다르면 409 `IDEMPOTENCY_KEY_REUSED`. ORIGIN이고 응답이 저장됐으면 재생, 아니면 표 A. ALIAS면 표 A | [0016](../../decisions/0016-payment-idempotency-in-progress.md), [0018](../../decisions/0018-payment-new-key-existing-payment.md) |
| 4 | 결제 토큰 검증(아래 항목)과 금액 대조. `exp`가 지났으면 "조회 전용"으로 표시하고 5단계로 간다(만료 뒤 24시간 넘으면 403) | 403 `PAYMENT_TOKEN_INVALID`, 400 `PAYMENT_AMOUNT_MISMATCH` | 0024, 0018 |
| 5 | 토큰의 예매 ID로 기존 결제 조회(`UNIQUE(reservation_id)`) | 있으면 표 B. 없고 조회 전용이면 403 `PAYMENT_TOKEN_INVALID` | [0015](../../decisions/0015-payment-declined-token-reuse.md), 0018, [0025](../../decisions/0025-payment-one-per-reservation.md) |
| 6 | `pg-approve` Bulkhead·Circuit Breaker 허가 획득(신규 승인만) | 503 `PG_UNAVAILABLE`(행·멱등 기록 없음) | [0017](../../decisions/0017-payment-pg-permit-before-tx1.md) |
| 7 | TX1: 멱등 기록(ORIGIN)을 먼저, 결제 `REQUESTED`(`next_check_at` = 현재 + 10초)를 다음에 저장하고 커밋 | 유니크 위반은 정상 흐름이다. 허가를 반환하고 롤백한 뒤 3단계부터 다시 조회한다. 같은 키면 3단계, 다른 키로 같은 예매면 5단계에서 응답한다(500 아님). DB 연결 대기 1초`[가정]` 초과·DB 오류는 허가 반환 후 503 `SERVICE_UNAVAILABLE`(행 없음) | 0016, 0017, 0025 |
| 8 | PG 호출 직전 토큰 `exp` 재검사 | 지났으면 PG를 부르지 않고 `DECLINED(TOKEN_EXPIRED)` + `PaymentDeclined`, 200. 허가는 호출 결과로 세지 않고 반환 | 0017 |
| 9 | PG 승인 호출(트랜잭션 밖, 연결 0.5초·응답 2초), 허가 정산 | `Approved`·`Declined`·`Unknown` | 0017, [0022](../../decisions/0022-payment-pg-client-port.md) |
| 10 | TX2: 승인 금액·통화 대조 뒤 조건부 UPDATE(`WHERE status = 'REQUESTED'`). 영향 행 1이면 이력 + Outbox(승인·거절만) + 멱등 응답(200·202) | 0행이면 표 A(현재 상태). 금액·통화 불일치면 `NEEDS_MANUAL_CHECK(PG_AMOUNT_MISMATCH)`, 이벤트 없음, 표 A. DB 오류면 202(`REQUESTED`)로 응답하고 재확인 스케줄러가 10초 뒤 복구 | [0021](../../decisions/0021-outbox-retry-and-retention.md), 0022 |

### 응답

표 A. 결제의 현재 상태로 응답한다(같은 키 재요청, ALIAS 키, TX2 0행, 금액 불일치).

| 결제 상태 | HTTP | `data` |
|---|---|---|
| `REQUESTED`·`UNKNOWN` | 202 + `Location: /api/v1/payments/{id}` | `{paymentId, reservationId, status, nextPollAfterSeconds}` |
| `APPROVED` | 200 | `{paymentId, reservationId, status, amount, approvedAt}` |
| `DECLINED` | 200 | `{paymentId, reservationId, status, reasonCode}` |
| `REFUNDED` | 200 | `{paymentId, reservationId, status, amount, refundedAt}` |
| `NEEDS_MANUAL_CHECK` | 409 `PAYMENT_ALREADY_EXISTS` + `Location` | 에러 본문 |

표 B. 같은 예매에 결제가 이미 있을 때(새 키 요청, TX1 예매 충돌).

| 기존 결제 상태 | 처리 |
|---|---|
| `DECLINED`·`REFUNDED` | 409 `PAYMENT_ALREADY_CLOSED`(`Location` 없음). 기록을 남기지 않는다 |
| 그 밖 | 핵심 정보 대조: 인증 사용자 = 결제 `user_id`, 토큰 예매 ID = 결제 `reservation_id`, 요청 금액 = 토큰 금액 = 결제 `amount`, 통화 = 결제 `currency`, 수단 = 결제 `method`. 하나라도 다르면 409 `PAYMENT_ALREADY_EXISTS`(`Location` 없음, 기존 결제 정보 미노출). 모두 같으면 새 키를 ALIAS로 저장하고 표 A로 응답 |

- 결제 거절은 에러가 아니다(200 + `DECLINED`). `reasonCode`는 우리 `DeclineReason` 값이다(PG 원본 코드는 로그에만).
- 202의 `status`는 `REQUESTED` 또는 `UNKNOWN`이다. 클라이언트는 둘을 같은 "확인 중"으로 다루고 `nextPollAfterSeconds`(10초) 뒤 조회한다.
- 멱등 기록은 두 가지다. ORIGIN은 TX1까지 진행된 요청이고, ALIAS는 표 B에서 기존 결제에 연결된 새 키다. 저장되는 응답은 ORIGIN의 200·202뿐이다. TX1 전의 4xx·503은 기록이 없어 같은 요청을 다시 보내면 같은 결과가 나온다.
- **Gateway 504와 재시도**: 승인 요청의 최악 처리 시간은 TX1(연결 대기 1초 이내) + PG 2.5초 + TX2(연결 대기 1초 이내)로 Gateway 응답 시한 3초(SA 9-4)를 넘을 수 있다. 넘으면 클라이언트는 504를 받지만 Payment의 처리는 계속되고 PG 호출·TX2는 취소되지 않는다`[가정, N29로 확인]`. 클라이언트는 같은 키로 다시 보내 표 A로 결과를 받는다. 새 키로 보내도 표 B로 같은 결제에 연결되고 PG를 다시 부르지 않는다. Gateway에는 POST 재시도 필터를 두지 않는다.
- **승인은 예매 확정이 아니다**: `APPROVED`는 돈이 승인됐다는 뜻이다. 예매 확정과 티켓은 `GET /api/v1/reservations/{id}`로 확인한다(반영 목표 2초, SA 9-4).

### 토큰 검증 항목

| 항목 | 검증 |
|---|---|
| 서명 | HMAC-SHA256 고정(헤더 `alg`를 믿지 않고 `none`·다른 알고리즘 거절). 키는 `PAYMENT_TOKEN_SECRET` 문자열의 UTF-8 바이트(0024). 서명 비교는 상수시간 |
| 용도·대상 | `typ=payment`, `aud=payment-service` |
| 만료 | `exp` 허용 오차 0초. 지났으면 신규 승인 불가, 기존 결제 조회만(만료 뒤 24시간 이내) |
| 사용자 | `sub` = `X-User-Id` |
| 예매·금액 | 예매 ID로 결제 대상을 정한다. 금액은 요청 금액과 같아야 한다 |
| 식별 | `jti`는 로그·추적용 |
| 길이 | 4,096자 이하`[가정]` |

claim 이름은 상수 한 곳에 두고 반효승이 통보한 값으로 채운다(U1).

## 2. 결제 조회 `GET /api/v1/payments/{paymentId}`

인증이 필요하다. 응답 `data`: `{paymentId, reservationId, status, amount, approvedAt, refundedAt}`. `status`에 `NEEDS_MANUAL_CHECK`가 올 수 있다. 결제 토큰이 필요 없어 토큰이 만료된 뒤에도 결과를 확인할 수 있다. 예매 확정 여부는 이 응답에 없다(위 "승인은 예매 확정이 아니다").

접근 제어([0020](../../decisions/0020-payment-object-level-authorization.md)):
- 일반 사용자는 `WHERE id = :id AND user_id = :userId`로 조회한다. ADMIN은 `id`만으로 조회한다. ADMIN 여부는 Gateway가 검증한 역할로만 판단한다.
- 타인의 결제와 존재하지 않는 결제는 같은 404 `NOT_FOUND`다(상태·코드·메시지·본문 구조 동일, ID를 응답에 넣지 않음).
- 타인 결제 접근 시도는 내부 로그에 WARN으로 남긴다(요청 사용자, 대상 `paymentId`, `traceId`).
- `paymentId`를 받는 모든 API는 소유자 조건이 있는 조회 메서드만 쓴다. 조건 없는 `findById`를 외부 요청 경로에서 부르지 않는다.

## 3. 에러 코드

SA 11-3의 결제 코드에 아래를 더하거나 바꾼다(`ErrorCode`를 구현한 `PaymentErrorCode` enum).

| 코드 | HTTP | 언제 | 구분 |
|---|---|---|---|
| `VALIDATION_FAILED` | 400 | 요청 형식 오류, `CARD`가 아닌 `method` | 기존(`CommonErrorCode`) |
| `PAYMENT_TOKEN_INVALID` | 403 | 서명 오류, 만료(신규 승인), `typ`·`aud`·`sub` 불일치, 길이 초과, 만료 24시간 초과 | 기존 |
| `PAYMENT_AMOUNT_MISMATCH` | 400 | 요청 금액 ≠ 토큰 금액 | 기존 |
| `PAYMENT_ALREADY_EXISTS` | 409 | `NEEDS_MANUAL_CHECK` 결제가 있거나(`Location` 있음) 요청 정보가 기존 결제와 다름(`Location` 없음) | 의미 좁힘 |
| `PAYMENT_ALREADY_CLOSED` | 409 | 같은 예매에 `DECLINED`·`REFUNDED` 결제가 있음 | 신규 |
| `PG_UNAVAILABLE` | 503 | `pg-approve` Circuit Breaker OPEN·Bulkhead 초과 | 기존 |
| `SERVICE_UNAVAILABLE` | 503 | TX1의 DB 연결 대기 초과·DB 오류(행 없음) | 기존(`CommonErrorCode`) |
| `IDEMPOTENCY_KEY_REUSED` | 409 | 같은 키에 다른 본문 | 기존(`CommonErrorCode`) |
| `NOT_FOUND` | 404 | 결제 없음 또는 타인 결제 | 기존(`CommonErrorCode`) |
| `BILLING_KEY_REQUIRED` | 409 | 빌링키 없음(M4) | 기존 |

- `PAYMENT_ALREADY_CLOSED`의 사용자 안내는 "이 예매의 결제는 종료됐다. 새로 선점해 다시 시도한다"이다.
- 유니크 위반(`DataIntegrityViolationException`)을 500으로 내보내지 않는다. 어떤 제약이 걸렸는지(멱등 기록 PK, `reservation_id`, M4의 `pg_tx_id`)로 나눠 위 처리 순서 7단계대로 다시 조회한다. `pg_tx_id` 충돌(다른 결제의 `paymentKey`를 보낸 요청)의 응답 코드는 M4에서 정한다.
