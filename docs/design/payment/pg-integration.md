# PG 연동

PG 호출은 `PgClient` 포트 뒤에 두고, 서비스 코드는 PG별 요청·응답·에러 코드를 보지 못한다. 어느 PG로 보낼지는 결제마다 정해지며 `PgClients` 포트가 고른다(1절). 결정 근거와 토스 문서 확인 내용은 [0023](../../decisions/0023-payment-pg-client-port.md), 호출 자원 분리는 [0018](../../decisions/0018-payment-pg-permit-before-tx1.md)에 있다. 지키는 조건은 INV-1·INV-2·INV-3·INV-8([invariants.md](invariants.md))이다.

## 1. 포트

`application` 계층의 `PgClient`가 정의하고 `infrastructure`의 어댑터가 구현한다.

**포트를 고르는 방식(`PgClients`, ISE-231)**: 서비스는 `PgClient`를 직접 주입받지 않고 `PgClients.of(PgProvider)`로 어댑터를 얻는다. 어느 PG인지는 결제의 `payments.pg_provider`가 정하므로 승인·재확인·환불 워커·Ops 조회가 모두 `of(payment.pgProvider)`로 부른다. 설정 `payment.pg.provider=mock|toss`는 서버 기본 PG일 뿐이고, 승인 요청의 `pgProvider`가 없을 때와 checkout의 `provider`에만 쓰인다.

| 호출하는 곳 | 어느 PG로 보내나 |
|---|---|
| 승인 API(`PaymentApprovalService`) | TX1이 결제에 기록한 `pg_provider` |
| 재확인(`PaymentRecheckService`)의 조회·늦은 승인 방지 | claim한 결제의 `pg_provider` |
| 환불 워커(`RefundWorkerService`)의 환불·환불 조회 | 환불 대상 결제의 `pg_provider` |
| Ops 명령(`OpsPgLookup`, `inspect --with-pg`) | 조회하는 결제의 `pg_provider` |
| 빌링키 발급·자동결제 승인 | 늘 Mock(`PgClients.billing()`). 빌링 결제의 `pg_provider`도 `MOCK`이라 재확인·환불도 Mock으로 간다 |

- Mock 어댑터는 늘 만든다. 토스 어댑터는 토스 시크릿 키가 설정돼 있을 때 만들고, `provider=mock`이어도 키가 있으면 만든다(모드를 되돌린 뒤에도 진행 중인 토스 결제를 조회하려고).
- 설정에 없는 PG(`TOSS` 결제인데 토스 키 없음)를 달라면 `PgClients.of`는 PG를 부르지 않고 모든 호출에 `Unknown(CLIENT_ERROR)`로 답하는 클라이언트를 돌려준다. 우리 쪽 설정 문제라 복구 허가를 반환하고 서킷 브레이커에 세지 않는다(`PG_ERROR`면 공유 CB가 열려 Mock 결제의 재확인·환불까지 멈춘다). 재확인 스케줄러는 죽지 않고 결과 모름 경로를 탄다. Mock으로 대신 보내지 않는다.
- 승인 허가와 복구 허가(서킷 브레이커·벌크헤드)와 PG 지표 태그는 PG 구분 없이 하나를 같이 쓴다(감수 사항, ADR 0023 2026-11-10 개정).

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
| `Approved`·`AlreadyApproved` | `amount = payments.amount`, `currency = payments.currency` | 결제 `NEEDS_MANUAL_CHECK(PG_AMOUNT_MISMATCH)`. `pg_tx_id`·`pg_approved_amount`·`approved_at`을 저장하고 `PaymentApproved`를 내지 않는다. 즉시 알림. 코드가 이 검사를 빠뜨려도 `chk_payments_approved`가 `APPROVED` 저장을 막는다 |
| `Refunded` | `amount = refunds.amount`, `currency = payments.currency` | 환불 `NEEDS_MANUAL_CHECK(REFUND_AMOUNT_MISMATCH)`, `PaymentRefunded` 없음, 즉시 알림 |

금액이 다른 승인은 예매를 확정하지 않는다. 고객 돈은 PG에 묶여 있으므로 운영자가 Ops `payment resolve`로 PG 승인을 다시 확인하고 PG 승인 금액 전액을 환불한다([manual-recovery.md](manual-recovery.md) 2절).

## 3. 식별자

| 이름 | 누가 만드나 | DB | Mock | 토스 |
|---|---|---|---|---|
| `PgOrderId` | 우리 | `payments.pg_order_id` | `orderId` | `orderId` |
| `PgPaymentRef` | PG | `payments.pg_tx_id` | `pgTxId` | `paymentKey` |
| `PgRefundId` | 우리 | `refunds.pg_refund_id` | `refundId` | 취소 요청의 멱등키로 사용 |
| `PgIdempotencyKey` | 우리(결정적 파생) | 저장하지 않음 | 승인 = `pg_order_id`, 환불 = `pg_refund_id` | `Idempotency-Key` 헤더 |

`clientPaymentRef`는 구매자가 결제창에서 받은 PG 발급 식별자(토스 `paymentKey`)다. 토스는 필수이고 Mock은 무시한다. 토스 모드의 TX1은 이 값을 `pg_tx_id`에 먼저 저장해 응답을 잃어도 같은 식별자로 재확인한다.

토스는 결제창을 열 때 `orderId`가 필요하므로 TX1보다 먼저 정해져야 한다. 결제 토큰에서 결정적으로 만든다: `PgOrderIds.of(reservationId, token.exp)`가 이름 기반 UUID를 돌려준다. 저장 없이 `POST /api/v1/payments/checkout`과 TX1이 같은 값을 얻고, TX1은 모드와 상관없이 이 값을 `pg_order_id`로 쓴다. 빌링 경로는 토큰이 없어 무작위 UUID를 그대로 쓴다([0023](../../decisions/0023-payment-pg-client-port.md) 2026-11-09 개정).

## 4. 호출 규칙

- `approve`는 결제 행 하나에 한 번, 승인 API 요청 스레드만 부른다. 재확인은 `findPayment`·`preventLateApproval`만 쓰고 같은 주문을 다시 승인하지 않는다. 토스 승인의 멱등 지원이 확인되지 않아도 같은 주문이 두 번 승인되지 않게 하는 구조다(INV-1).
- `refund`는 환불 워커만 부른다. `payment.command` 소비자, DLQ 처리기, Ops 명령은 환불 행만 만들거나 상태를 되돌리고 PG 환불을 부르지 않는다([0028](../../decisions/0028-payment-ops-commands.md)).
- 환불을 다시 부르기 전에는 `findRefund`로 먼저 확인하고 `NotFound`일 때만 같은 `pg_refund_id`로 다시 부른다. 토스 멱등키 유효기간(15일)이 지난 뒤의 재시도도 이 순서가 이중 환불을 막는다(INV-2).
- `NotFound`인 결제는 `preventLateApproval`이 `Prevented`를 돌려준 뒤에만 `DECLINED(NOT_SENT)`로 닫는다(INV-8).

## 5. 호출 보호

PG 호출 자원은 호출 특성과 장애 영향으로 나눈다. 승인과 복구 두 그룹에 더해 추첨 자동결제 전용 그룹을 둔다([0018](../../decisions/0018-payment-pg-permit-before-tx1.md), 자동결제 분리는 0018 개정(2026-11-12)과 추첨 쪽 ADR 0051). 값은 모두 `[가정]`이며 부하 측정 뒤 조정한다.

| 그룹 | 호출 | 호출자 | Bulkhead | Circuit Breaker | 허가를 못 얻으면 |
|---|---|---|---|---|---|
| `pg-approve` | `approve` | 승인 API | 동시 30, 대기 0(설정으로 받는다. 코드 기본값은 10이고 `config-repo`가 30으로 덮는다. 아래 "승인 동시 허가 수와 대기 시간") | 최근 20회 중 실패율 50% 이상이면 OPEN, OPEN 10초, HALF_OPEN 시험 3회 | 503 `PG_UNAVAILABLE`, 행 없음 |
| `pg-billing` | `approveBilling` | `payment.command` 소비자(추첨 자동결제, ADR 0051) | 동시 3(소비자 동시성과 같게), 대기 0 | 같은 기준, 상태는 별도 | 503이 아니라 같은 레코드를 1초 간격으로 다시 읽음(무제한, 행을 만들지 않음, 그 파티션의 뒤 메시지만 멈춤) |
| `pg-recovery` | `findPayment`, `preventLateApproval`, `refund`, `findRefund` | 재확인 스케줄러, 환불 워커, Ops 명령(조회) | 동시 2(복구 실행기 스레드 수) | 같은 기준, 상태는 별도 | 다음 주기로 넘기고 시도 횟수를 세지 않음. Ops 명령은 거부 |

- 타임아웃은 모든 그룹이 연결 0.5초·응답 2초다(SA 9-4).
- 결과를 모르는 `Unknown`(`TIMEOUT`·`CONNECTION_LOST`·`PG_ERROR`·`PROCESSING`)은 CB에 실패로 기록하고, 정상 거절과 `NotFound`는 성공으로 센다. `Unknown(CLIENT_ERROR)`(요청·인증을 거절한 4xx)는 셈하지 않고 허가만 반환한다. 분류는 `PgPermit.settle` 한 곳이 맡는다([0018](../../decisions/0018-payment-pg-permit-before-tx1.md) 개정).
- 허가 획득과 정산은 PG 호출 래퍼 한 곳에서 `try/finally`로 묶는다. 승인은 TX1 앞에서 얻고 TX1이 실패하면 호출 결과로 세지 않고 반환한다.
- Ops 명령은 별도 프로세스라 같은 설정의 별도 Resilience4j 인스턴스를 쓴다(세 그룹이 모두 생긴다).
- 빌링키 발급(`BillingKeyService`의 `issueBillingKey`)은 어느 그룹도 쓰지 않는다. Bulkhead·CB 없이 Mock PG 어댑터를 바로 부르고 결과 모름이면 503 `PG_UNAVAILABLE`로 답한다. 이번에 바꾸지 않았다.
- 자동결제는 사용자가 기다리지 않는 배경 호출이라 허가를 못 얻어도 사용자 응답이 없다. 그래서 일반 결제처럼 503으로 끝내지 않고 소비자가 같은 레코드를 다시 읽는다. 일반 결제와 자리를 나눠 추첨 자동결제 폭주가 일반 결제의 자리를 빼앗지 않고, 반대도 마찬가지다.
- 503 `PG_UNAVAILABLE`은 PG 장애만 뜻하지 않는다. 허가는 TX1부터 PG 호출 정산까지 Bulkhead 자리를 잡으므로 DB(TX1)가 느려도 자리가 차서 같은 503이 날 수 있다. 원인은 Bulkhead 가용 수(`resilience4j_bulkhead_available_concurrent_calls{name="pg-approve"}`)와 TX1 지연을 함께 본다(ISE-156 지표). 건수는 `payment_approve_seconds_count{outcome="unavailable"}`다.
- 구현(ISE-37): `resilience4j-circuitbreaker`·`resilience4j-bulkhead` 2.3.0을 Spring 통합 없이 코어로 쓴다. 승인 그룹은 `PgApprovalGate`(허가를 얻어 `PgPermit`으로 정산), 복구 그룹은 `PgRecoveryGate`다. `tryAcquire`는 허가만 얻고(못 얻으면 빈 값) 호출한 쪽이 `callStarted`·`settle`·`close`를 맡으며, `execute`는 그 위에서 허가·호출·정산을 한 번에 묶는다. 허가를 못 얻으면 호출하지 않고 시도 횟수를 세지 않는다. 재확인 스케줄러는 `tryAcquire`로 "허가 먼저, claim 나중"을 지킨다([events-and-reliability.md](events-and-reliability.md) 6절). 설정은 `payment.pg.protection.{approve,recovery,billing}.*`다. 자동결제 그룹은 `PgBillingGate`다(ISE-236). 복구 그룹의 호출자 중 재확인 스케줄러는 ISE-38에서, 환불 워커는 ISE-137에서 붙였다.
- PG 호출은 트랜잭션 밖이고 TX1 커밋 뒤에 한다.

### 승인 동시 허가 수와 대기 시간 (ISE-238, 2026-11-12 배포 측정)

`pg-approve`의 Bulkhead 값은 두 가지다. 설정은 `payment.pg.protection.approve.max-concurrent-calls`와 `max-wait-duration`이다(`PgProtectionProperties`).

**허가 수가 정하는 것 두 가지**

- 처리량의 상한: 동시에 진행 중인 승인 수 L = 초당 도착 λ × 한 건이 머무는 시간 W(리틀의 법칙)이고, 허가 수가 L의 상한이다. 그래서 최대 처리량은 `허가 수 ÷ (TX1 + PG 응답 시간)`이다. PG 300ms에서 허가 10이면 초당 약 31건이다.
- 장애 때 묶이는 스레드 수의 상한: PG가 느려지면 허가를 쥔 요청 스레드가 PG 응답을 기다린다. 허가 수는 그 스레드 수의 최대값이다. Tomcat 요청 스레드(기본 200) 중 이 수만큼만 PG에 묶이고 나머지는 조회·재시도 응답을 계속 처리한다.

두 값이 한 숫자에 걸려 있어 처리량을 올리면 장애 때 묶이는 스레드도 는다. 그래서 필요한 처리량에서 값을 정한다.

**필요한 값**: 대기열이 초당 50명을 들인다(`admit-per-second: 50`). 필요한 허가 ≈ 결제 도착률 50 × PG p99 0.35초 × 여유 2 ≈ 35다.

**측정**([open-peak-scenarios.md](../infra/open-peak-scenarios.md) 3절 "PAY1 PG 동시 허가 30과 짧은 대기 200ms")

| 설정 | PG 300ms에서 승인 한도 | 요청 80/초에서 거절 | 과부하 때 승인 p50 | 과부하 때 거절 응답 p50 |
|---|---|---|---|---|
| 허가 10, 대기 0(지금 값) | 초당 31.2건 | 59.5% | 311ms | 즉시 |
| 허가 30, 대기 0 | 초당 91.5건 | 0.2% | 309ms | 3ms |
| 허가 30, 대기 200ms | 초당 98.5건 | 0% | 491ms | 203ms |

허가 30에서도 Hikari 활성 연결은 최대 3개(풀 20), 대기 0이었다. PG 호출을 트랜잭션 밖에서 하므로 허가 수가 DB 연결 수요로 이어지지 않는다.

**결정**

- 일반 결제 `pg-approve`는 30으로 올린다(`config-repo/payment-service.yml`에 반영했다, ISE-238). PG가 느린 경우(PAY1-b: 지연 1,000·1,900·2,500ms)는 허가 30으로 배포에서 잰 뒤 24~30 안에서 확정한다(ISE-246). PG가 응답 시한(2초)을 넘기면 결과를 모르는 `UNKNOWN` 결제가 허가 수에 비례해 쌓이고 복구 그룹(동시 2)이 그것을 치우므로, 이 구간을 보고 정한다.
- 추첨 자동결제는 전용 그룹 `pg-billing`(동시 3)을 쓴다(ISE-236). 일반 결제 30과 합쳐 PG를 동시에 부르는 호출은 일반 30 + 자동결제 3 + 복구 2 = 최대 35다. 토스의 동시 호출 한도는 확인하지 못했다. Mock PG에는 한도가 없고, 토스 모드의 상한은 배포 전에 토스 문서나 지원에 확인해야 한다.
- 대기 시간은 0으로 둔다. 대기를 주면 포화 때 자리를 기다리는 요청이 스레드를 쥐고, 그때 승인되는 결제가 모두 대기한 만큼 늦어지며 거절도 그만큼 늦게 나간다. 허가 30에서는 순간 몰림 거절이 이미 0이라 얻는 것이 과부하 때 승인 7.7%뿐이다.
- 대기 시간 설정은 코드에 남긴다(기본 0, 0 이상 1초 이하만 받는다). 실제 PG처럼 지연 편차가 큰 환경에서 다시 잴 때 코드를 고치지 않고 값만 바꾼다. 1초 상한은 승인 한 건의 시간 예산(아래)이 Gateway 3초 안에 들어와야 해서다.

**Erlang B로 본 거절 확률**: 리틀의 법칙은 평균 동시 수를 주고, Erlang B는 무작위로 도착하는 요청이 대기 없는 허가(손실 시스템)에 부딪혀 거절될 확률을 준다. 도착이 포아송이면 평균 동시 수가 허가 수보다 작아도 순간 몰림으로 일부가 거절된다. 아래는 허가를 쥐는 시간 0.315초(TX1 + PG 300ms 등) 기준 계산값이다(측정이 아니다). 제공 부하 a = 초당 도착 × 0.315다.

| 초당 도착 | 허가 10 | 허가 17 | 허가 20 | 허가 24 | 허가 27 | 허가 30 |
|---|---|---|---|---|---|---|
| 30건 (a ≈ 9.5) | 18.9% | 0.85% | 0.10% | - | - | - |
| 50건 (a ≈ 15.8) | - | 13.4% | 5.9% | 1.3% | 0.28% | 0.05% |

k6의 일정한 도착은 포아송보다 몰림이 적어 같은 허가 수에서 거절이 더 낮게 나온다. 위 측정(허가 30에서 0.2%)은 그래서 이 계산값보다 낙관적이다. 계산식과 코드는 [학습 노트](../../learning/junhyun/qna/2026-11-12-erlang-b-bulkhead-sizing.md)에 있다.

**하지 않은 것**: 결제 서비스 인스턴스 증설(허가가 인스턴스마다 따로라 2대면 2배지만 단일 EC2의 메모리 예산 밖이다), PG 호출 비동기화(스레드가 병목이 아니다. 허가 30에서 JVM 스레드 최대 112).

시간 예산: 승인 요청 한 건은 TX1(연결 대기 1초 이내) + PG 최대 2.5초 + TX2(연결 대기 1초 이내)라 Gateway 응답 시한 3초를 넘을 수 있다. 넘으면 클라이언트는 504를 받고 서버는 처리를 계속한다. 같은 키 재시도로 결과를 받는다([api.md](api.md) 응답 절, N29). PG 호출 전 구간(TX1까지)은 DB가 응답 없이 멈춰도 연결을 빌릴 때 1초(대기·생존 검사), 빌린 연결의 첫 문장에서 2초(소켓 시한) 안에 끝나 503으로 응답한다. DB가 멈추지 않고 느리기만 하면 문장마다 1.5초까지 걸려 3초를 넘을 수 있다([events-and-reliability.md](events-and-reliability.md) 7절 DB 시한). TX2 쪽은 위 설명 그대로다(3초를 넘으면 504, 같은 키 재시도).

## 6. 어댑터와 계약 테스트

- M2: `MockPgAdapter`, 공통 계약 테스트. Mock PG(결제 담당, 10/28 역할 변경)는 환불 조회·환불 멱등·망취소와 호출 단위 장애 주입(SA의 `NONE·DELAY·DECLINE·DROP_RESPONSE`에 계약 테스트용 모드를 더함, [mock-pg.md](mock-pg.md) 5절, S-29)을 제공한다(U16).
- M4(ISE-45): `TossPgAdapter`. 같은 공통 계약 테스트를 가짜 토스 서버(`FakeTossServer`, JDK `HttpServer`)로 통과하고(`TossPgAdapterContractTest`), 토스 전용 분류·로그 테스트를 더한다. 토스 테스트 환경을 실제로 부르는 통합 테스트(`TossPgAdapterSandboxTest`, `@Tag("toss-sandbox")`)는 기본 `test`에서 빠지고 `TOSS_TEST_SECRET_KEY` 환경변수가 있을 때 `./gradlew :payment-service:tossSandboxTest`로만 돈다. 테스트 키는 저장소에 두지 않는다.
- 공통 계약 테스트의 `guaranteesLateApprovalPrevention()` 훅(기본 true)이 false인 어댑터(토스)는 승인되지 않은 주문의 늦은 승인 방지에서 `Prevented` 대신 `Unknown`을 기대한다.
- 계약 테스트 항목: 승인 성공(금액·통화 포함), 명확한 거절, 타임아웃, 같은 멱등키 재시도, 결제 조회 미발견, 환불 성공(금액·통화 포함), 환불 거절, 환불 결과 불확실, 환불 조회 미발견 뒤 재호출, 처리 중 응답, `CLIENT_ERROR`, 늦은 승인 방지, 응답에 금액·통화가 없을 때 `Unknown`.
- 장애 유형별 통합 테스트([failure-scenarios.md](failure-scenarios.md) 4절)는 Mock PG의 원장(주문별 승인 호출 수, 결제별 환불 수와 금액)을 읽어 중복을 검사한다. Mock PG에 `test`·`loadtest` 프로필 전용 원장 조회를 둔다(S-28).

### Mock PG 프로토콜(어댑터가 가정하는 응답)

SA 11-5에 S-28(금액·통화)을 더한 모양이다. 실제 Mock PG 서버를 만들 때 이 표를 따르고, `PgClientContractTest`는 `MockPgServerContractTest`가 상속해 실제 mock-pg 앱을 상대로 돌린다(`PgScenario`는 장애 주입 API로 구현). 가짜 서버(`FakeMockPgServer`)는 프로토콜을 흉내 내지 않고, 실제 앱이 만들 수 없는 본문 코드 없는 404만 답한다(`MockPgAdapterContractTest`).

| 호출 | 성공 응답 | 어댑터 해석 |
|---|---|---|
| POST `/mock-pg/payments` `{orderId, amount, currency}` | 200 `{pgTxId, status: APPROVED, amount, currency, approvedAt}` | `Approved` |
| 〃 | 200 `{status: DECLINED, declineCode}` | `Declined`(`declineCode`를 `DeclineReason`으로 매핑, 모르면 `OTHER`) |
| 〃 | 202 또는 `status: PROCESSING` | `Unknown(PROCESSING)` |
| 〃 | 409 `{code: ORDER_CANCELED}`(망취소된 주문) | `Declined(NOT_SENT)` |
| 〃 | 409 `{code: REQUEST_IN_PROGRESS}`(같은 주문의 승인이 처리 중) 등 그 밖의 409 | `Unknown(PROCESSING)`(거절로 단정하지 않는다) |
| GET `/mock-pg/payments/{orderId}` | 위 승인·거절 응답. 승인이 처리 중이면 202 `{status: PROCESSING}`. 없거나 망취소된 주문은 404 `{code: NOT_FOUND}` | `Approved`·`Declined`·`Unknown(PROCESSING)`·`NotFound` |
| POST `/mock-pg/payments/{orderId}/cancel` | `{status: CANCELED}`(이미 거절된 주문에도 같은 응답이고 상태는 거절 그대로), 이미 승인됐으면 승인 응답, 승인이 처리 중이면 202 | `Prevented` · `AlreadyApproved` · `Unknown(PROCESSING)` |
| POST `/mock-pg/refunds` `{pgTxId, refundId, amount, currency, reason}` | 200 `{refundTxId, pgTxId, status: REFUNDED, amount, currency, refundedAt}` | `Refunded`(`refundTxId`·`pgTxId` 필수, `pgTxId`는 요청과 같아야 함) |
| 〃 | 422 `{status: REJECTED, code}` | `RefundRejected`(`NOT_REFUNDABLE`·`OTHER`) |
| GET `/mock-pg/refunds/{refundId}` | 환불 응답. 없으면 404 `{code: NOT_FOUND}` | `Refunded`·`NotFound` |

서버가 지켜야 할 것:
- 같은 `orderId`·`refundId`의 재요청은 저장된 결과를 돌려준다(멱등).
- 승인 저장과 망취소는 원자적이다. `CANCELED`를 응답한 주문은 이후 승인을 저장하지 않으며, 승인이 처리 중인 주문에 취소가 오면 `CANCELED`가 아니라 202를 준다(INV-8). 상세 설계는 [mock-pg.md](mock-pg.md)다.
- 응답 본문의 `code`·`status`는 어댑터가 분류에 쓰므로 서버가 항상 직접 만든다(mock-pg.md 11절).
- 원장을 메모리에만 두면 재시작 때 사라져 `NotFound`·`CANCELED`가 나온다. 장애·부하 테스트에서 Mock PG를 재시작한다면 원장을 유지하거나 테스트 가정으로 적는다.

어댑터의 공통 규칙: 5xx와 해석할 수 없는 응답, 승인·환불 응답의 필수 필드(`pgTxId`·`refundTxId`·`amount`·`currency`·시각) 누락, 정수가 아닌 금액은 `Unknown(PG_ERROR)`다. 그 밖의 4xx와 본문 코드가 없는 404는 `Unknown(CLIENT_ERROR)`다. 응답 시간 초과는 `TIMEOUT`, 연결 실패와 응답 없이 끊김은 `CONNECTION_LOST`다. 금액은 BigDecimal로 파싱한다(double이면 소수 오차가 일치로 보인다).

### 토스 프로토콜(어댑터가 가정하는 응답)

공식 문서(docs.tosspayments.com) 기준이다. `FakeTossServer`가 같은 모양을 흉내 낸다. 인증은 모든 호출에 `Authorization: Basic base64(secretKey + ":")`이다.

| 호출 | 응답 | 어댑터 해석 |
|---|---|---|
| POST `/v1/payments/confirm` `{paymentKey, orderId, amount}` + `Idempotency-Key`(= `pg_order_id`) | 200 Payment `status: DONE`, `paymentKey` 일치, 정수 `totalAmount`·`currency`·`approvedAt` | `Approved`. 그 밖의 200(다른 상태, `paymentKey` 불일치, 필드 누락, 정수 아닌 금액)은 `Unknown(PG_ERROR)` |
| 〃 | 403 `REJECT_CARD_COMPANY` | `Declined(CARD_DECLINED)` |
| 〃 | 403 `REJECT_CARD_PAYMENT`, 400 `EXCEED_MAX_*` | `Declined(INSUFFICIENT_LIMIT)` |
| 〃 | 404 `NOT_FOUND_PAYMENT_SESSION`(인증 뒤 10분 지남) | `Declined(OTHER)` |
| 〃 | 400 `ALREADY_PROCESSED_PAYMENT`, 409 `IDEMPOTENT_REQUEST_PROCESSING` 등 409 | `Unknown(PROCESSING)`(재확인 조회가 확정) |
| 〃 | 400 `PROVIDER_ERROR`, 5xx | `Unknown(PG_ERROR)` |
| 〃 | 401 `UNAUTHORIZED_KEY`, 그 밖의 4xx, 모르는 코드 | `Unknown(CLIENT_ERROR)`(우리 쪽 설정·코드 문제, 알림 대상) |
| GET `/v1/payments/orders/{orderId}` | 200 Payment. `DONE` / `ABORTED` / `EXPIRED` / `IN_PROGRESS`·`READY` / `CANCELED`·`PARTIAL_CANCELED` | `Approved` / `Declined` / `NotFound` / `Unknown(PROCESSING)` / `Unknown(PG_ERROR)`와 경고 |
| 〃 | 404 `NOT_FOUND_PAYMENT` | `NotFound`. 코드 없는 404는 `Unknown(CLIENT_ERROR)` |
| `preventLateApproval` | 위 조회를 쓴다 | `DONE`이면 `AlreadyApproved`, 나머지는 모두 `Unknown`(토스에는 망취소 API가 없어 `Prevented`를 내지 않는다) |
| POST `/v1/payments/{paymentKey}/cancel` `{cancelReason, cancelAmount}` + `Idempotency-Key`(= `pg_refund_id`) | 200 Payment, `cancels[]`의 완료 건 하나 | `Refunded(transactionKey, cancelAmount, currency, canceledAt)`. 필드 누락·`paymentKey` 불일치·완료 건이 여럿이면 `Unknown(PG_ERROR)` |
| 〃 | 400 `ALREADY_CANCELED_PAYMENT`, 403 `NOT_CANCELABLE_*` | `RefundRejected(NOT_REFUNDABLE)` |
| 〃 | 409, 5xx, 그 밖의 4xx | `Unknown`(`PROCESSING`·`PG_ERROR`·`CLIENT_ERROR`) |
| `findRefund`: GET `/v1/payments/{paymentKey}` | 완료된 취소 건 하나 / 없음 / 여럿 | `Refunded` / `NotFound` / `Unknown(PG_ERROR)` |
| `issueBillingKey`·`approveBilling` | 호출하지 않는다. 빌링은 서버 모드와 상관없이 늘 Mock으로 보낸다(2026-11-10) | 방어용으로 `Unknown(CLIENT_ERROR)` / `Declined(NOT_SENT)`(보내지 않았으므로 확실히 승인되지 않음)를 남겨 둔다 |

시간 초과는 `Unknown(TIMEOUT)`, 연결 실패와 응답 없이 끊김은 `Unknown(CONNECTION_LOST)`다(Mock과 같다). 응답 시각은 `+09:00` 오프셋 ISO 8601이라 `OffsetDateTime`으로 읽는다. 시크릿 키는 헤더로만 보내고 로그·예외·`toString`에 남기지 않는다.

### 건너뛰기 설정의 기본값

토스 모드의 Mock 건너뛰기(`payment.pg.mock-bypass-enabled`)는 꺼진 것이 기본이다. 시연 편의 기능은 꺼진 상태로 두고 환경에서 명시적으로 켠다. 설정을 빠뜨리거나 환경변수가 전달되지 않아도(compose가 변수를 넘기지 않는 실수 포함) 결제창을 거치는 쪽으로 동작하게 하려는 것이다. 켜는 쪽이 기본이면 같은 실수가 결제 없는 승인으로 이어진다. Mock 모드에서는 모든 결제가 Mock이라 이 설정의 영향이 없다. 켜고 끄는 절차는 [deployment.md](../infra/deployment.md) 4절 "결제 PG 모드 전환"에 있다.

## 7. 토스 어댑터가 확인할 것
ISE-45에서 가짜 토스 서버로 구현했고 아래 중 "문서"로 표시한 것은 공식 문서 기준이다. 실제 호출로 확인하지 못한 것(`[가정]`)은 테스트 키를 받은 뒤(11/11) `tossSandboxTest`와 시연으로 확인하고 ADR 0023 개정 절을 갱신한다.

| 항목 | 상태 |
|---|---|
| 5(승인 API가 `paymentKey`를 받는 필드), 6(`orderId` 사전 발급 방식) | 해결: `clientPaymentRef`와 `POST /api/v1/payments/checkout`, `PgOrderIds`(3절, [api.md](api.md)) |
| 7(4xx 분류) | 문서 기준으로 구현(위 표). 실제 오류 코드 목록과 다른 코드는 `Unknown(CLIENT_ERROR)`로 안전하게 떨어진다 |
| 4(`preventLateApproval`) | 토스에 망취소 API가 없어 `Prevented`를 내지 않고 `Unknown`으로 둔다. 거짓 보장을 하지 않는다 |
| 8(`findRefund`, 금액·통화 필드, 재취소 응답) | 문서 기준으로 구현. `findRefund`는 결제당 전액 환불 한 번이라는 전제로 완료 취소 건 하나를 본다 `[가정]` |
| 1(confirm 멱등키), 2(타임아웃), 3(`paymentKey` 만료·최대 길이) | `[가정]` 미확인. 어댑터는 기존 연결 0.5초·응답 2초를 쓴다 |

원래 확인 항목(참고):
1. 승인(confirm)이 멱등키를 지원하는지, 같은 키 다른 본문과 오류 응답 재생 동작.
2. 승인 API 권장 타임아웃.
3. 인증 뒤 `paymentKey` 만료 시간(문서에 10분과 30분이 엇갈림)과 `paymentKey` 최대 길이(`pg_tx_id` 컬럼).
4. 승인 응답 유실 시 복구 절차와 `preventLateApproval`을 `Prevented`로 판정할 근거. 별도 망취소 API는 문서에서 찾지 못했다. 확인 전에는 `Prevented`를 반환하지 않는다.
5. 승인 API에 구매자의 `paymentKey`를 받는 필드를 추가해야 한다([sa-change-requests.md](sa-change-requests.md) S-14).
6. `orderId` 사전 발급 방식(결제창을 열기 전에 필요)과 결제 토큰·예매와의 연결.
7. 4xx 응답 분류: 명확한 거절 코드만 `Declined`로, 요청·인증 오류는 `Unknown(CLIENT_ERROR)`로, 이미 처리된 결제 응답은 조회로 확인한다.
8. 승인·조회·취소 응답의 금액·통화 필드, 우리 `pg_refund_id`로 취소를 찾는 방법(`findRefund` 구현), 이미 전액 취소된 결제의 재취소 응답.
