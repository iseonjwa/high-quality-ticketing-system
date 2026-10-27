# 0022. PG 호출은 PgClient 포트와 PG 독립 결과 타입으로 나누고, 어댑터는 같은 계약 테스트를 통과한다

- 상태: 제안 (SA 변경 요청 필요, 토스 관련 항목은 M4 시작 때 확인 후 갱신. 2026-10-28 개정)
- 날짜: 2026-10-27
- 결정자: 안준현
- 관련 AI 판단: [결제 상세 설계 빈틈 결정](../ai-judgments/junhyun/2026-10-27-payment-design-gaps.md)
- 관련 결정: [0017](0017-payment-pg-permit-before-tx1.md), [0019](0019-payment-refund-dlq-manual-check.md), [0025](0025-payment-one-per-reservation.md), [0027](0027-payment-ops-commands.md)
- 불변 조건: INV-1, INV-2, INV-3, INV-8([invariants.md](../design/payment/invariants.md))

## 맥락

SA는 M2에 Mock PG(이강민)를 쓰고 M4에 토스페이먼츠 테스트 모드 어댑터를 붙이기로 한다(6-6). SA 11-5의 Mock PG API는 우리 쪽 식별자(`orderId` = `pg_order_id`, `refundId` = `pg_refund_id`)를 멱등키로 쓰고, 조회 404, 망취소 `POST /mock-pg/payments/{orderId}/cancel`(경로는 팀 확인)을 가진다. 호출 코드를 Mock API 모양대로 짜면 M4에 승인·상태 전이 코드까지 고치게 된다.

2026-10-28 검토에서 결과 타입에 실제 승인·환불 금액과 통화가 없어 PG가 다른 금액을 처리해도 알 수 없다는 점(H2·M5), 환불 응답 유실 뒤 조회에서 `NotFound`가 나오면 할 일이 정해지지 않았다는 점(H5), 이중 환불 방어가 PG 멱등키 하나에 기댄다는 점(H6), 토스 `orderId`를 결제창 전에 정해야 한다는 점(M9)이 나왔다.

### 토스페이먼츠 공식 문서에서 확인한 것 (2026-10-27)
출처: [코어 API](https://docs.tosspayments.com/reference), [멱등키](https://docs.tosspayments.com/guides/using-api/idempotency-key), [결제 흐름 이해하기](https://docs.tosspayments.com/guides/v2/get-started/payment-flow)

- 흐름이 다르다. 구매자가 클라이언트 결제창에서 인증하면 **토스가 `paymentKey`를 발급**하고, 서버는 `POST /v1/payments/confirm`(`paymentKey`, `orderId`, `amount`)으로 승인한다. Mock은 서버가 `orderId`와 금액만 보내 바로 승인한다. 우리 `POST /api/v1/payments`(SA 11-5)에는 `paymentKey`를 받는 자리가 없다.
- 식별자 셋이 다르다: `orderId`(가맹점이 만들고 저장), `paymentKey`(토스 발급, 승인·조회·취소에 쓰는 거래 식별자), `Idempotency-Key`(POST 호출 단위 헤더).
- 조회는 `GET /v1/payments/{paymentKey}`와 `GET /v1/payments/orders/{orderId}`다. `orderId` 조회에는 API 개별 연동 키(`test_sk`)가 필요하고 결제위젯·결제창용 키로는 호출할 수 없다.
- 취소는 `POST /v1/payments/{paymentKey}/cancel`(`cancelReason` 필수, `cancelAmount` 생략 시 전액)이다. **문서에서 "망취소"라는 별도 API는 찾지 못했다.**
- `Idempotency-Key`는 모든 POST에 쓸 수 있고 **15일** 유효하다. 같은 키 요청에는 같은 응답을 돌려주며, 처리 중이면 409 `IDEMPOTENT_REQUEST_PROCESSING`이다. 중복 판단 기준에 요청 본문은 들어 있지 않다.

### 문서로 확정하지 못한 것 (M4 시작 때 테스트 환경에서 확인)
- 승인(confirm) API가 멱등키를 지원한다고 문서가 직접 말하는지(모든 POST라는 서술로만 추정).
- 같은 키에 다른 본문을 보냈을 때의 동작, 오류 응답을 재생하는지.
- 승인 API의 권장 타임아웃(자동결제 승인은 60초 이상 권장이라는 서술만 있다).
- 인증 뒤 `paymentKey`가 만료되는 시간(문서 안에 10분과 30분이 엇갈린다).
- 승인 응답을 못 받았을 때 토스가 권하는 복구 절차(공식 문서에서 명시한 곳을 찾지 못했다).

## 결정

1. **포트와 결과 타입**: `payment-service`의 application 계층에 `PgClient` 인터페이스와 PG 독립 결과 타입을 두고, infrastructure 계층에 `MockPgAdapter`와 `TossPgAdapter`를 둔다. 어떤 어댑터를 쓸지는 설정(`payment.pg.provider=mock|toss`)으로 고른다. 서비스 코드는 PG별 요청·응답 모양, HTTP 상태, 에러 코드를 보지 못한다.
2. **결과 타입을 호출별로 나누고 금액·통화를 담는다**(sealed 타입):

   | 호출 | 결과 |
   |---|---|
   | 승인 `approve` | `Approved(paymentRef, amount, currency, approvedAt)` · `Declined` · `Unknown` |
   | 결제 조회 `findPayment` | `Approved(…)` · `Declined` · `NotFound` · `Unknown` |
   | 환불 `refund` | `Refunded(refundRef, amount, currency, refundedAt)` · `RefundRejected` · `Unknown` |
   | 환불 조회 `findRefund` | `Refunded(…)` · `RefundRejected` · `NotFound` · `Unknown` |
   | 늦은 승인 방지 `preventLateApproval` | `Prevented` · `AlreadyApproved(paymentRef, amount, currency, approvedAt)` · `Unknown` |

   `Declined`는 PG가 **명확히 거절한** 경우만이다. 거절 사유는 우리 `DeclineReason`으로 바꿔 담고(어댑터가 매핑, 매핑 못 한 코드는 `OTHER`), PG 원본 코드는 로그에만 남긴다. 금액·통화는 PG가 응답한 값이다. 응답에 없으면 어댑터는 값을 지어내지 않고 `Unknown(PG_ERROR)`로 둔다.
3. **불확실하면 `Unknown`**: 타임아웃, 연결이 끊김, 요청은 보냈지만 응답을 못 받음, 응답을 해석하지 못함, PG 5xx, "처리 중"(토스 409 `IDEMPOTENT_REQUEST_PROCESSING`)은 모두 `Unknown`이다. 원인 분류(`TIMEOUT`, `CONNECTION_LOST`, `PG_ERROR`, `PROCESSING`, `CLIENT_ERROR`)를 담아 재확인 스케줄러와 지표가 쓴다. 연결 전 실패처럼 미전송이 확실한 경우도 일단 `Unknown`으로 둔다(보수적, 최적화는 나중에). PG가 요청 형식·인증을 거절한 4xx(우리 쪽 설정·코드 문제)는 거절이 아니라 `Unknown`(`CLIENT_ERROR`)으로 두고 알림을 낸다. 결제를 `DECLINED`로 닫지 않는다.
4. **결과 검증**: 서비스는 승인 결과의 금액·통화를 결제의 `amount`·`currency`와, 환불 결과를 환불의 `amount`·결제 `currency`와 대조한다. 승인 불일치는 결제를 `NEEDS_MANUAL_CHECK(PG_AMOUNT_MISMATCH)`로 두고 `pg_approved_amount`를 저장하며 `PaymentApproved`를 내지 않는다. 환불 불일치는 환불 `NEEDS_MANUAL_CHECK(REFUND_AMOUNT_MISMATCH)`다. 둘 다 즉시 알린다. DB의 `chk_payment_approved`(`pg_approved_amount = amount`)가 코드 누락을 한 번 더 막는다. 금액이 다른 승인은 예매를 확정하지 않고 PG 승인 금액 전액을 환불한다(Ops `payment resolve`, [0027](0027-payment-ops-commands.md)).
5. **어댑터가 변환한다**: PG의 HTTP 상태와 자체 에러 코드는 어댑터 안에서 결과 타입으로 바꾼다.
6. **식별자 구조**(토스의 세 식별자를 반영):
   - `PgOrderId`: 우리가 발급하는 주문 식별자(`payment.pg_order_id`). 토스 `orderId`. 예매당 결제가 하나라([0025](0025-payment-one-per-reservation.md)) 토스 결제창 전에 예매마다 하나를 정할 수 있다. 방식(결제 토큰 claim 또는 예매 ID에서 결정적 파생)은 M4에서 정한다.
   - `PgPaymentRef`: PG가 발급하는 불투명 거래 식별자(`payment.pg_tx_id`, UNIQUE). Mock `pgTxId`, 토스 `paymentKey`. 서비스 코드는 값의 의미를 해석하지 않는다.
   - `PgIdempotencyKey`: PG 호출 한 건의 재시도 식별자. 승인은 `pg_order_id`에서, 환불은 `pg_refund_id`에서 **결정적으로 만든다**(같은 호출을 다시 보내도 같은 키). 어댑터가 PG별 헤더·필드로 옮기고, 지원하지 않는 PG에서는 멱등을 조회로 보완한다.
   - 승인 명령은 `ApproveCommand(orderId, amount, currency, idempotencyKey, clientPaymentRef?)`다. `clientPaymentRef`는 구매자가 결제창에서 받은 PG 발급 식별자다. 토스는 필수이고 Mock은 무시한다. 있으면 **TX1에서 `pg_tx_id`에 먼저 저장**해, 응답을 잃어도 같은 키로 재확인할 수 있게 한다.
7. **호출 규칙**:
   - `approve`는 결제 행 하나에 한 번, 승인 API만 부른다. 재확인은 `findPayment`·`preventLateApproval`만 쓴다. 토스 승인의 멱등이 확인되지 않아도 같은 주문의 재승인이 없다.
   - `refund`는 환불 워커만 부른다(0019). 소비자·DLQ 처리기·Ops 명령은 부르지 않는다.
   - 환불을 다시 부르기 전에는 `findRefund`로 먼저 확인하고, `NotFound`일 때만 같은 `pg_refund_id`로 다시 부른다. 이중 환불 방어가 PG 멱등키 하나에 기대지 않고, 토스 멱등키 유효기간(15일)이 지난 뒤의 재시도도 막는다.
   - 호출 자원은 `approve`는 `pg-approve`, 나머지는 `pg-recovery` 그룹이다([0017](0017-payment-pg-permit-before-tx1.md)).
8. **망취소는 PG가 지원하는 방식으로만 추상화한다**: Mock에만 있는 "취소 뒤 같은 주문의 승인을 거부"하는 동작을 포트에 강제하지 않는다. 포트는 목적을 말하는 `preventLateApproval(orderId)`를 둔다("승인 여부가 불확실한 주문이 나중에 승인되지 않음을 보장하거나, 이미 승인됐음을 알린다"). Mock 어댑터는 망취소 API로 구현한다. 토스 어댑터는 별도 망취소 API가 문서에 없으므로, 위 확인 항목을 M4에서 확인한 뒤 어떤 절차로 `Prevented`를 판정할지 정한다. 확인 전에는 토스 어댑터가 `Prevented`를 반환하지 않는다(`Unknown`).
9. **공통 계약 테스트**: 어댑터마다 같은 테스트 묶음을 통과해야 한다. 포함 항목은 승인 성공(금액·통화), 명확한 거절, 타임아웃, 같은 멱등 키 재시도(같은 결과), 결제 조회 미발견, 환불 성공(금액·통화), 환불 거절, 환불 결과 불확실(응답 유실), 환불 조회 미발견 뒤 재호출, 처리 중 응답, 요청 형식 오류(`CLIENT_ERROR`), 늦은 승인 방지, 응답에 금액·통화가 없을 때 `Unknown`이다.
10. **테스트 계층**: Mock 어댑터는 Mock PG의 장애 주입(`NONE·DELAY·DECLINE·DROP_RESPONSE`, SA 11-5)으로 위 시나리오를 재현한다. 장애 유형별 통합 테스트는 Mock PG 원장(주문별 승인 수, 결제별 환불 수·금액)으로 중복을 검사한다. 토스 어댑터는 M4에서 HTTP 스텁 테스트와 토스 테스트 환경 통합 테스트(테스트 키 사용, 기본 CI 밖의 별도 실행)를 따로 한다. 테스트 키는 저장소에 두지 않는다.
11. **일정**: M2에는 포트, 결과 타입, `MockPgAdapter`, 계약 테스트를 구현한다. M4에 `TossPgAdapter`를 추가한다.

## 검토한 대안

- **포트 + 결과 타입만(계약 테스트 없음)**: 구현이 가장 작다. 어댑터마다 타임아웃 분류와 멱등 처리가 달라져도 사람이 일일이 확인해야 해서, 돈이 걸린 경로에는 부족하다.
- **Mock PG 얇은 래퍼**: M2가 가장 빠르다. 토스의 `paymentKey` 흐름과 멱등 헤더 때문에 M4에 승인·복구 코드를 다시 짜야 한다.
- **결과에 금액을 담지 않고 요청 금액을 믿음(원안)**: 타입이 단순하다. PG가 다른 금액을 승인·환불해도 알 수 없다.

## 트레이드오프

| | 얻는 것 | 감수하는 것 |
|---|---|---|
| 포트 + 결과 타입 + 계약 테스트 | PG 교체 비용과 회귀 위험이 작다. 불확실성 처리가 한 곳에 모인다 | M2 작업량이 늘고, Mock에서 검증한 가정이 토스에서 어긋날 수 있다 |
| `Unknown`을 보수적으로 | 결제를 잘못 거절하지 않는다(돈 사고 방지) | 불필요하게 재조회 대상이 되는 건이 늘 수 있다 |
| 결과의 금액·통화 대조 | PG와 우리 원장의 금액 차이를 즉시 안다 | Mock PG 응답 형식을 바꿔야 한다(이강민) |
| 재호출 전 조회 | 이중 환불 방어가 두 겹이 된다 | 재시도마다 조회 호출이 하나 는다 |
| 망취소를 목적 중심 메서드로 | 토스처럼 별도 API가 없어도 같은 서비스 로직을 쓴다 | 토스에서 `Prevented`를 보장할 방법이 문서로 확정되지 않아 M4에 불확실성이 남는다 |
| 결정적 PG 멱등키 | 서버가 죽어도 같은 호출을 안전하게 반복할 수 있다 | 키 파생 규칙을 바꾸면 진행 중 건과 어긋난다 |

## 결과

- SA 변경 요청: S-11(`decline_code`, `pg_tx_id` 길이), S-14(`clientPaymentRef`, 토스 `orderId`), S-19(환불 호출 규칙), S-28(Mock PG 응답의 `amount`·`currency`와 테스트 원장).
- 이강민(Mock PG): 환불 조회·환불 멱등·망취소(U16)를 포트 계약에 맞추고, 응답에 금액·통화를 넣고, 테스트 전용 원장 조회를 둔다. Mock이 토스의 멱등 동작(같은 키 같은 응답, 처리 중 409)을 흉내 낼지는 협의한다.
- `pg-integration.md`에 포트·결과 타입·결과 검증·호출 규칙·계약 테스트 목록을 쓴다.
- M4 시작 시 "문서로 확정하지 못한 것" 다섯 가지와 `pg-integration.md` 7절의 추가 항목(`orderId` 사전 발급, 4xx 분류, 금액·통화 필드, `findRefund` 구현, 전액 취소된 결제의 재취소 응답)을 토스 테스트 환경에서 확인하고 이 ADR을 갱신한다.
- 테스트: E9, N16, N19, N20, FT-2.

## 개정 이력

- 2026-10-28: (1) 승인·환불 결과에 실제 금액·통화를 넣고 불일치 처리 규칙을 정했다(검토 H2·M5, 사용자 지정 수정). (2) 환불 조회 `NotFound` 뒤 같은 `pg_refund_id`로 재호출하는 규칙과 재호출 전 조회를 넣었다(H5·H6). (3) `approve`·`refund`를 부를 수 있는 곳을 하나씩으로 정했다(H6). (4) 토스 `orderId` 사전 발급과 4xx 분류를 M4 확인 항목에 넣었다(M9). (5) 호출 자원 그룹 대응을 적었다(0017).

## 면접에서 설명할 한 줄

PG를 갈아끼울 수 있게 포트로 감싸되, 인터페이스를 Mock이 아니라 "결제의 불확실성"을 중심으로 설계했고(타임아웃은 거절이 아니라 Unknown), PG가 실제로 처리한 금액까지 결과로 받아 우리 원장과 대조하며, 환불은 다시 보내기 전에 먼저 조회해 이중 환불을 막았다.
