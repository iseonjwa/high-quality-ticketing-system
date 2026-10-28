# PG 연동

PG 호출은 `PgClient` 포트 뒤에 두고, 서비스 코드는 PG별 요청·응답·에러 코드를 보지 못한다. 결정 근거와 토스 문서 확인 내용은 [0023](../../decisions/0023-payment-pg-client-port.md), 호출 자원 분리는 [0018](../../decisions/0018-payment-pg-permit-before-tx1.md)에 있다. 지키는 조건은 INV-1·INV-2·INV-3·INV-8([invariants.md](invariants.md))이다.

## 1. 포트

`application` 계층의 `PgClient`가 정의하고 `infrastructure`의 어댑터가 구현한다. 설정 `payment.pg.provider=mock|toss`로 고른다.

| 메서드 | 입력 | 결과(sealed) |
|---|---|---|
| `approve` | `ApproveCommand(orderId, amount, currency, idempotencyKey, clientPaymentRef?)` | `Approved(paymentRef, amount, currency, approvedAt)` · `Declined(reason)` · `Unknown(cause)` |
| `findPayment` | `PgOrderId` | `Approved(...)` · `Declined(reason)` · `NotFound` · `Unknown(cause)` |
| `refund` | `RefundCommand(paymentRef, refundId, amount, currency, reason, idempotencyKey)` | `Refunded(refundRef, amount, currency, refundedAt)` · `RefundRejected(reason)` · `Unknown(cause)` |
| `findRefund` | `PgRefundId`, `PgPaymentRef` | `Refunded(...)` · `RefundRejected(reason)` · `NotFound` · `Unknown(cause)` |
| `preventLateApproval` | `PgOrderId` | `Prevented` · `AlreadyApproved(paymentRef, amount, currency, approvedAt)` · `Unknown(cause)` |

- `Declined`는 PG가 명확히 거절한 경우만이다. 거절 사유는 우리 `DeclineReason`(매핑 못 하면 `OTHER`)이고 PG 원본 코드는 로그에만 남긴다.
- `Unknown(cause)`의 원인: `TIMEOUT`, `CONNECTION_LOST`, `PG_ERROR`(5xx, 해석 불가 응답), `PROCESSING`(처리 중 응답), `CLIENT_ERROR`(요청 형식·인증 거절, 알림 대상). 미전송이 확실해도 일단 `Unknown`으로 둔다.
- 승인·환불 결과에는 PG가 응답한 실제 금액과 통화가 들어간다. 응답에 금액·통화가 없으면 어댑터는 값을 지어내지 않고 `Unknown(PG_ERROR)`로 둔다. SA 11-5의 Mock PG 응답에는 금액·통화가 없어 Mock PG에 응답 필드를 추가한다(S-28, 결제 담당).
- HTTP 상태와 PG 에러 코드의 해석은 어댑터 안에서 끝낸다.

## 2. 결과 검증

어댑터가 돌려준 금액·통화를 서비스가 결제·환불 행과 대조한다(INV-3).

| 결과 | 대조 | 불일치 처리 |
|---|---|---|
| `Approved`·`AlreadyApproved` | `amount = payment.amount`, `currency = payment.currency` | 결제 `NEEDS_MANUAL_CHECK(PG_AMOUNT_MISMATCH)`. `pg_tx_id`·`pg_approved_amount`·`approved_at`을 저장하고 `PaymentApproved`를 내지 않는다. 즉시 알림. 코드가 이 검사를 빠뜨려도 `chk_payment_approved`가 `APPROVED` 저장을 막는다 |
| `Refunded` | `amount = refund.amount`, `currency = payment.currency` | 환불 `NEEDS_MANUAL_CHECK(REFUND_AMOUNT_MISMATCH)`, `PaymentRefunded` 없음, 즉시 알림 |

금액이 다른 승인은 예매를 확정하지 않는다. 고객 돈은 PG에 묶여 있으므로 운영자가 Ops `payment resolve`로 PG 승인을 다시 확인하고 PG 승인 금액 전액을 환불한다([manual-recovery.md](manual-recovery.md) 2절).

## 3. 식별자

| 이름 | 누가 만드나 | DB | Mock | 토스 |
|---|---|---|---|---|
| `PgOrderId` | 우리 | `payment.pg_order_id` | `orderId` | `orderId` |
| `PgPaymentRef` | PG | `payment.pg_tx_id` | `pgTxId` | `paymentKey` |
| `PgRefundId` | 우리 | `refund.pg_refund_id` | `refundId` | 취소 요청의 멱등키로 사용 |
| `PgIdempotencyKey` | 우리(결정적 파생) | 저장하지 않음 | 승인 = `pg_order_id`, 환불 = `pg_refund_id` | `Idempotency-Key` 헤더 |

`clientPaymentRef`는 구매자가 결제창에서 받은 PG 발급 식별자다. 토스는 필수이고 Mock은 무시한다. 있으면 TX1에서 `pg_tx_id`에 저장해 응답을 잃어도 같은 키로 재확인한다. 토스는 결제창을 열 때 `orderId`가 필요하므로 TX1보다 먼저 정해져야 한다. 예매당 결제가 하나라서([0026](../../decisions/0026-payment-one-per-reservation.md)) 예매마다 `orderId` 하나를 미리 정할 수 있다(결제 토큰 claim에 넣거나 예매 ID에서 결정적으로 파생). 방식은 M4에서 정한다(5절 #6).

## 4. 호출 규칙

- `approve`는 결제 행 하나에 한 번, 승인 API 요청 스레드만 부른다. 재확인은 `findPayment`·`preventLateApproval`만 쓰고 같은 주문을 다시 승인하지 않는다. 토스 승인의 멱등 지원이 확인되지 않아도 같은 주문이 두 번 승인되지 않게 하는 구조다(INV-1).
- `refund`는 환불 워커만 부른다. `payment.command` 소비자, DLQ 처리기, Ops 명령은 환불 행만 만들거나 상태를 되돌리고 PG 환불을 부르지 않는다([0028](../../decisions/0028-payment-ops-commands.md)).
- 환불을 다시 부르기 전에는 `findRefund`로 먼저 확인하고 `NotFound`일 때만 같은 `pg_refund_id`로 다시 부른다. 토스 멱등키 유효기간(15일)이 지난 뒤의 재시도도 이 순서가 이중 환불을 막는다(INV-2).
- `NotFound`인 결제는 `preventLateApproval`이 `Prevented`를 돌려준 뒤에만 `DECLINED(NOT_SENT)`로 닫는다(INV-8).

## 5. 호출 보호

PG 호출 자원은 호출 특성과 장애 영향으로 두 그룹만 나눈다([0018](../../decisions/0018-payment-pg-permit-before-tx1.md)). 값은 모두 `[가정]`이며 부하 측정 뒤 조정한다.

| 그룹 | 호출 | 호출자 | Bulkhead | Circuit Breaker | 허가를 못 얻으면 |
|---|---|---|---|---|---|
| `pg-approve` | `approve` | 승인 API | 동시 10, 대기 0 | 최근 20회 중 실패율 50% 이상이면 OPEN, OPEN 10초, HALF_OPEN 시험 3회 | 503 `PG_UNAVAILABLE`, 행 없음 |
| `pg-recovery` | `findPayment`, `preventLateApproval`, `refund`, `findRefund` | 재확인 스케줄러, 환불 워커, Ops 명령(조회) | 동시 2(복구 실행기 스레드 수) | 같은 기준, 상태는 별도 | 다음 주기로 넘기고 시도 횟수를 세지 않음. Ops 명령은 거부 |

- 타임아웃은 두 그룹 모두 연결 0.5초·응답 2초다(SA 9-4).
- `Unknown`과 PG 5xx는 CB에 실패로 기록하고, 정상 거절과 `NotFound`는 성공으로 센다.
- 허가 획득과 정산은 PG 호출 래퍼 한 곳에서 `try/finally`로 묶는다. 승인은 TX1 앞에서 얻고 TX1이 실패하면 호출 결과로 세지 않고 반환한다.
- Ops 명령은 별도 프로세스라 같은 설정의 별도 Resilience4j 인스턴스를 쓴다.
- Resilience4j 의존성은 아직 `payment-service/build.gradle`에 없어 추가한다.
- PG 호출은 트랜잭션 밖이고 TX1 커밋 뒤에 한다.

시간 예산: 승인 요청 한 건은 TX1(연결 대기 1초 이내) + PG 최대 2.5초 + TX2(연결 대기 1초 이내)라 Gateway 응답 시한 3초를 넘을 수 있다. 넘으면 클라이언트는 504를 받고 서버는 처리를 계속한다. 같은 키 재시도로 결과를 받는다([api.md](api.md) 응답 절, N29).

## 6. 어댑터와 계약 테스트

- M2: `MockPgAdapter`, 공통 계약 테스트. Mock PG(결제 담당, 10/28 역할 변경)는 장애 주입 `NONE·DELAY·DECLINE·DROP_RESPONSE`, 환불 조회·환불 멱등·망취소를 제공한다(U16).
- M4: `TossPgAdapter`. HTTP 스텁 테스트(오류·타임아웃)와 토스 테스트 환경 통합 테스트(테스트 키, 기본 CI 밖)를 따로 한다. 테스트 키는 저장소에 두지 않는다.
- 계약 테스트 항목: 승인 성공(금액·통화 포함), 명확한 거절, 타임아웃, 같은 멱등키 재시도, 결제 조회 미발견, 환불 성공(금액·통화 포함), 환불 거절, 환불 결과 불확실, 환불 조회 미발견 뒤 재호출, 처리 중 응답, `CLIENT_ERROR`, 늦은 승인 방지, 응답에 금액·통화가 없을 때 `Unknown`.
- 장애 유형별 통합 테스트([failure-scenarios.md](failure-scenarios.md) 4절)는 Mock PG의 원장(주문별 승인 수, 결제별 환불 수와 금액)을 읽어 중복을 검사한다. Mock PG에 테스트 프로필 전용 원장 조회를 둔다(S-28).

### Mock PG 프로토콜(어댑터가 가정하는 응답)

SA 11-5에 S-28(금액·통화)을 더한 모양이다. 실제 Mock PG 서버를 만들 때 이 표를 따른다. `MockPgAdapterContractTest`의 가짜 서버가 같은 프로토콜을 흉내 낸다.

| 호출 | 성공 응답 | 어댑터 해석 |
|---|---|---|
| POST `/mock-pg/payments` `{orderId, amount, currency}` | 200 `{pgTxId, status: APPROVED, amount, currency, approvedAt}` | `Approved` |
| 〃 | 200 `{status: DECLINED, declineCode}` | `Declined`(`declineCode`를 `DeclineReason`으로 매핑, 모르면 `OTHER`) |
| 〃 | 202 또는 `status: PROCESSING` | `Unknown(PROCESSING)` |
| 〃 | 409(망취소된 주문) | `Declined(NOT_SENT)` |
| GET `/mock-pg/payments/{orderId}` | 위 승인·거절 응답, 없으면 404 | `Approved`·`Declined`·`NotFound` |
| POST `/mock-pg/payments/{orderId}/cancel` | `{status: CANCELED}` 또는 승인 응답 | `Prevented` · `AlreadyApproved` |
| POST `/mock-pg/refunds` `{pgTxId, refundId, amount, currency, reason}` | 200 `{refundTxId, pgTxId, status: REFUNDED, amount, currency, refundedAt}` | `Refunded` |
| 〃 | 422 `{status: REJECTED, code}` | `RefundRejected`(`NOT_REFUNDABLE`·`OTHER`) |
| GET `/mock-pg/refunds/{refundId}` | 환불 응답, 없으면 404 | `Refunded`·`NotFound` |

공통: 5xx와 해석할 수 없는 응답, 승인 응답의 `pgTxId`·`amount`·`currency`·`approvedAt` 누락은 `Unknown(PG_ERROR)`, 그 밖의 4xx는 `Unknown(CLIENT_ERROR)`다. 응답 시간 초과는 `TIMEOUT`, 연결 실패·응답 없이 끊김은 `CONNECTION_LOST`다. 같은 `orderId`·`refundId`의 재요청은 저장된 결과를 돌려준다(멱등).

## 7. 토스 어댑터가 M4에서 확인할 것
1. 승인(confirm)이 멱등키를 지원하는지, 같은 키 다른 본문과 오류 응답 재생 동작.
2. 승인 API 권장 타임아웃.
3. 인증 뒤 `paymentKey` 만료 시간(문서에 10분과 30분이 엇갈림)과 `paymentKey` 최대 길이(`pg_tx_id` 컬럼).
4. 승인 응답 유실 시 복구 절차와 `preventLateApproval`을 `Prevented`로 판정할 근거. 별도 망취소 API는 문서에서 찾지 못했다. 확인 전에는 `Prevented`를 반환하지 않는다.
5. 승인 API에 구매자의 `paymentKey`를 받는 필드를 추가해야 한다([sa-change-requests.md](sa-change-requests.md) S-14).
6. `orderId` 사전 발급 방식(결제창을 열기 전에 필요)과 결제 토큰·예매와의 연결.
7. 4xx 응답 분류: 명확한 거절 코드만 `Declined`로, 요청·인증 오류는 `Unknown(CLIENT_ERROR)`로, 이미 처리된 결제 응답은 조회로 확인한다.
8. 승인·조회·취소 응답의 금액·통화 필드, 우리 `pg_refund_id`로 취소를 찾는 방법(`findRefund` 구현), 이미 전액 취소된 결제의 재취소 응답.
