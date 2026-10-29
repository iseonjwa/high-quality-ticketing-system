# 결제 API

승인과 조회 두 API를 둔다. 운영자 복구는 HTTP API가 아니라 Ops 명령이다([0028](../../decisions/0028-payment-ops-commands.md)). 빌링키 등록(`POST /api/v1/payments/billing-keys`)은 M4에서 설계한다. 응답은 `SuccessResponse`·`FailureResponse` 공통 형식을 따르고(SA 11-2), 아래 `data`는 그 안의 내용이다.

## 1. 결제 승인 `POST /api/v1/payments`

헤더: `Idempotency-Key`(UUID, 필수). 본문: `{paymentToken, amount, method: "CARD"}`. 인증이 필요하다. M4 토스 어댑터는 `clientPaymentRef`를 더 받는다([SA 변경 요청](sa-change-requests.md) S-14).

### 처리 순서

| # | 단계 | 실패 또는 분기 | 근거 |
|---|---|---|---|
| 1 | 인증 확인. `X-User-Id`는 Gateway가 검증한 값만 쓴다 | 401 | [0021](../../decisions/0021-payment-object-level-authorization.md) |
| 2 | 형식 검증: 키는 UUID, `method`는 `CARD`만(`BILLING`은 M4 자동결제 이벤트 전용), `amount`는 양의 정수, `paymentToken`은 4,096자 이하`[가정]` | 400 `VALIDATION_FAILED`. 토큰 길이 초과는 403 `PAYMENT_TOKEN_INVALID` | [0025](../../decisions/0025-payment-token-secret-injection.md) |
| 3 | `(user_id, Idempotency-Key)` 멱등 기록 조회. 토큰 검증보다 먼저, 허가 없이 한다 | 해시가 다르면 409 `IDEMPOTENCY_KEY_REUSED`. ORIGIN이고 응답이 저장됐으면 재생, 아니면 표 A. ALIAS면 표 A | [0017](../../decisions/0017-payment-idempotency-in-progress.md), [0019](../../decisions/0019-payment-new-key-existing-payment.md) |
| 4 | 결제 토큰 검증(아래 항목)과 금액 대조. `exp`가 지났으면 "조회 전용"으로 표시하고 5단계로 간다(만료 뒤 24시간 넘으면 403) | 403 `PAYMENT_TOKEN_INVALID`, 400 `PAYMENT_AMOUNT_MISMATCH` | 0025, 0019 |
| 5 | 토큰의 예매 ID로 기존 결제 조회(`UNIQUE(reservation_id)`) | 있으면 표 B. 없고 조회 전용이면 403 `PAYMENT_TOKEN_INVALID` | [0016](../../decisions/0016-payment-declined-token-reuse.md), 0019, [0026](../../decisions/0026-payment-one-per-reservation.md) |
| 6 | `pg-approve` Bulkhead·Circuit Breaker 허가 획득(신규 승인만) | 503 `PG_UNAVAILABLE`(행·멱등 기록 없음) | [0018](../../decisions/0018-payment-pg-permit-before-tx1.md) |
| 7 | TX1: 멱등 기록(ORIGIN)을 먼저, 결제 `REQUESTED`(`next_check_at` = 현재 + 10초)를 다음에 저장하고 커밋 | 유니크 위반은 정상 흐름이다. 허가를 반환하고 롤백한 뒤 3단계부터 다시 조회한다. 같은 키면 3단계, 다른 키로 같은 예매면 5단계에서 응답한다(500 아님). DB 연결 대기 1초`[가정]` 초과·DB 오류는 허가 반환 후 503 `SERVICE_UNAVAILABLE`(행 없음) | 0017, 0018, 0026 |
| 8 | PG 호출 직전 토큰 `exp` 재검사 | 지났으면 PG를 부르지 않고 `DECLINED(TOKEN_EXPIRED)` + `PaymentDeclined`, 200. 허가는 호출 결과로 세지 않고 반환 | 0018 |
| 9 | PG 승인 호출(트랜잭션 밖, 연결 0.5초·응답 2초), 허가 정산 | `Approved`·`Declined`·`Unknown` | 0018, [0023](../../decisions/0023-payment-pg-client-port.md) |
| 10 | TX2: 승인 금액·통화 대조 뒤 조건부 UPDATE(`WHERE status = 'REQUESTED'`). 영향 행 1이면 이력 + Outbox(승인·거절만) + 멱등 응답(200·202) | 0행이면 표 A(현재 상태). 금액·통화 불일치면 `NEEDS_MANUAL_CHECK(PG_AMOUNT_MISMATCH)`, 이벤트 없음, 표 A. DB 오류면 202(`REQUESTED`)로 응답하고 재확인 스케줄러가 10초 뒤 복구 | [0022](../../decisions/0022-outbox-retry-and-retention.md), 0023 |

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
| 서명 | HMAC-SHA256 고정(헤더 `alg`를 믿지 않고 `none`·다른 알고리즘 거절). 키는 `PAYMENT_TOKEN_SECRET` 문자열의 UTF-8 바이트(0025). 서명 비교는 상수시간 |
| 용도·대상 | `typ=payment`, `aud=payment-service` |
| 만료 | `exp` 허용 오차 0초. 지났으면 신규 승인 불가, 기존 결제 조회만(만료 뒤 24시간 이내) |
| 사용자 | `sub` = `X-User-Id` |
| 예매·금액 | 예매 ID로 결제 대상을 정한다. 금액은 요청 금액과 같아야 한다 |
| 식별 | `jti`는 로그·추적용 |
| 길이 | 4,096자 이하`[가정]` |

claim 이름은 상수 한 곳(`JwtPaymentTokenVerifier`가 쓰는 `PaymentTokenClaims`)에 두고 반효승이 통보한 값으로 채운다(U1).

### 구현 메모 (ISE-35)

- **토큰 claim 가정**: 통보를 기다리지 않고 SA 7-3의 서술에서 정한 값으로 구현했다. `sub`=사용자 ID, `reservationId`=예매 ID, `amount`=원화 정수 금액, `typ`=`payment`, `aud`=`payment-service`, `jti`·`iat`·`exp`는 표준 claim이다. HS256, 키는 `PAYMENT_TOKEN_SECRET`(0025). Reservation 발급기는 이 모양으로 만들거나, 다르면 `PaymentTokenClaims`를 고친다. `amount`는 소수 없는 숫자여야 하고 `reservationId`는 숫자여야 한다.
- **TX1의 쓰기 순서**: 7단계는 멱등 기록을 먼저 쓰라고 했지만 `idempotency_records.resource_id`가 NOT NULL이라 결제 ID가 필요해 결제를 먼저 쓴다. 한 트랜잭션이고 어느 유니크 위반이든 전체가 롤백돼 결과는 같다. 위반(`uq_payments_reservation_id`, `idempotency_records_pkey`) 뒤에는 3단계부터 한 번 다시 조회한다.
- **6단계 허가(ISE-37)**: 신규 승인 시도만 `pg-approve` 허가(Bulkhead·Circuit Breaker)를 TX1 앞에서 얻는다. 못 얻으면 503 `PG_UNAVAILABLE`이고 행·멱등 기록이 남지 않는다. 허가는 PG 호출 직후 결과로 정산하고(승인·거절은 성공, 결과를 모르면 실패), TX1 실패·만료 재검사로 PG를 부르지 않은 경우는 결과로 세지 않고 반환한다. 같은 키·기존 결제 응답은 허가 없이 나간다. 요청 처리 시간 제한(Gateway 3초)과 504 뒤 재시도는 Gateway 쪽 설정이고, 서버 처리는 클라이언트가 끊어도 계속된다(`PgProtectionApiTest`로 확인).
- **요청 해시**: 토큰 원문이 해시에 들어가므로(SA의 "같은 본문") 같은 키로 재발급된 다른 토큰을 보내면 409 `IDEMPOTENCY_KEY_REUSED`다. 새 키로 보내면 ALIAS로 결과를 받는다. Reservation이 같은 예매에 토큰을 재발급하는지 확정되면 해시 기준(예: `(reservationId, amount, method)`)을 다시 정한다(0017 순서 변경이라 ADR 개정 필요).
- **오류 분류**: PG 호출 전 구간(멱등 조회, 기존 결제 조회, TX1)의 DB 오류는 모두 503 `SERVICE_UNAVAILABLE`이다. PG 호출 뒤의 TX2는 어떤 예외든 롤백하고 202(`REQUESTED`)로 응답한다. `PgClient`가 예외를 던지면 결과를 모르는 것(`Unknown`)으로 다룬다. `amount`는 소수 없는 정수(15자리 이하)만 받는다.
- **알려진 경합**: 재확인 스케줄러(ISE-38)가 `REQUESTED`를 닫은 사이 요청 스레드의 PG 호출이 승인되면 TX2가 0행이 되고 승인 정보가 기록되지 않는다. 지금은 ERROR 로그(`PG_APPROVED_NOT_RECORDED`)만 남기며 복구 규칙은 ISE-38 설계에서 정한다.
- **응답 저장**: ORIGIN의 200(`APPROVED`·`DECLINED`)과 202(`UNKNOWN`)만 TX2에서 저장한다. TX2가 실패하면 저장하지 못하고 202(`REQUESTED`)로 응답하며 재확인 스케줄러(ISE-38)가 10초 뒤 확정한다.

## 2. 결제 조회 `GET /api/v1/payments/{paymentId}`

인증이 필요하다. 응답 `data`: `{paymentId, reservationId, status, amount, approvedAt, refundedAt}`. `status`에 `NEEDS_MANUAL_CHECK`가 올 수 있다. 결제 토큰이 필요 없어 토큰이 만료된 뒤에도 결과를 확인할 수 있다. 예매 확정 여부는 이 응답에 없다(위 "승인은 예매 확정이 아니다").

접근 제어([0021](../../decisions/0021-payment-object-level-authorization.md)):
- 일반 사용자는 `WHERE id = :id AND user_id = :userId`로 조회한다. ADMIN은 `id`만으로 조회한다. ADMIN 여부는 Gateway가 검증한 역할로만 판단한다.
- 타인의 결제와 존재하지 않는 결제는 같은 404 `NOT_FOUND`다(상태·코드·메시지·본문 구조 동일, ID를 응답에 넣지 않음).
- 타인 결제 접근 시도는 내부 로그에 WARN으로 남긴다(요청 사용자, 대상 `paymentId`, `traceId`).
- `paymentId`를 받는 모든 API는 소유자 조건이 있는 조회 메서드만 쓴다. 조건 없는 `findById`를 외부 요청 경로에서 부르지 않는다(ADMIN 조회 제외). 예외로, 일반 사용자의 소유자 조회가 비었을 때만 `existsById`로 존재를 확인해 WARN 로그를 구분한다. 이 확인은 로그 전용이라 응답을 나누지 않고, 실패해도 404를 유지한다.
- 응답은 여섯 필드를 항상 내보낸다. `approvedAt`은 `APPROVED`·`REFUNDED`만 값이 있고(금액 불일치로 수동 확인이 된 결제는 PG 승인 시각을 저장하지만 null), `refundedAt`은 `REFUNDED`만 값이 있다. 조회 중 DB 오류는 승인 API와 같이 503 `SERVICE_UNAVAILABLE`이다.

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
