# 0026. 예매 서비스는 결제 이벤트를 예매 원장과 대조해 상태별 규칙으로 처리한다

- 상태: 제안 (반효승 협의 필요, SA 변경 요청 필요)
- 날짜: 2026-10-28
- 결정자: 안준현(상태별 규칙은 사용자 결정. 사유 코드·필드 이름·격리 방식은 AI 정리). Reservation 소비자 계약이라 반효승 합의 전에는 확정이 아니다
- 관련 AI 판단: [결제 상세 설계 빈틈 결정](../ai-judgments/junhyun/2026-10-27-payment-design-gaps.md)
- 관련 결정: [0015](0015-payment-declined-token-reuse.md), [0019](0019-payment-refund-dlq-manual-check.md), [0021](0021-outbox-retry-and-retention.md), [0025](0025-payment-one-per-reservation.md), [0028](0028-payment-reservation-reconciliation.md)
- 불변 조건: INV-2, INV-3, INV-4([invariants.md](../design/payment/invariants.md))

## 맥락

0015는 Reservation에 "`HELD`가 아닌 예매(EXPIRED·RELEASED·CANCELED 등)에 `PaymentApproved`가 오면 `RefundRequested`"를 요청했다. 이 규칙에는 `CONFIRMED`도 들어간다. `PaymentApproved`가 재전달되면(Outbox 중복 전송, `processed_event` 정리 뒤 재전달) 이미 확정된 예매의 결제가 환불되어 고객은 티켓을 가진 채 돈을 돌려받는다(Opus 검토 C1).

또 SA 8-3은 이벤트의 금액·사용자를 예매 원장과 대조하라고 하지 않는다. 버그나 위조로 다른 금액·다른 사용자의 승인이 와도 확정된다(H2). SA 10-3의 이벤트에는 통화가 없다.

## 결정

Reservation의 `reservation.payment-result` 소비자는 이벤트의 `reservationId`·`paymentId`·`userId`·`amount`·`currency`를 예매 원장(`reservation`의 `user_id`·`total_amount`·`payment_id`)과 원화 상수 `KRW`에 대조하고, 예매 상태별로 아래처럼 처리한다.

### PaymentApproved

| 예매 상태 | 조건 | 처리 | 발행 | 알림 |
|---|---|---|---|---|
| `HELD` | 대조 통과, `approvedAt` ≤ `hold_expires_at` | `CONFIRMED`, `payment_id` 기록, 좌석별 티켓 | — | — |
| `HELD` | 대조 통과, `approvedAt` > `hold_expires_at` | 만료와 같은 전이(`EXPIRED`, 좌석 반환, 매수 한도 감소) | `RefundRequested(EXPIRED_AFTER_APPROVAL)` | — |
| `HELD` | 사용자·금액·통화 불일치 | 상태 유지(만료 스케줄러가 처리) | `RefundRequested(NOT_CONFIRMABLE)` | 즉시 |
| `CONFIRMED` | `paymentId` = 예매의 `payment_id` | 변경 없음(멱등) | — | — |
| `CONFIRMED` | `paymentId` 다름 | 변경 없음 | `RefundRequested(DUPLICATE_APPROVAL)` | 즉시 |
| `EXPIRED` | — | 변경 없음 | `RefundRequested(EXPIRED_AFTER_APPROVAL)` | 지표만 |
| `RELEASED` | — | 변경 없음 | `RefundRequested(NOT_CONFIRMABLE)` | 즉시(정상 경로에서는 생기지 않는다) |
| `CANCELED` | `paymentId` = 예매의 `payment_id` | 변경 없음(취소 환불은 이미 요청됨) | — | — |
| `CANCELED` | `paymentId` 다름 | 변경 없음 | `RefundRequested(DUPLICATE_APPROVAL)` | 즉시 |
| 예매 없음 | — | 이상 기록 | — | 즉시. Payment 대사 RC-2도 찾는다 |

- 만료 시각 판단은 PG 승인 시각(`approvedAt`)으로 한다. 예매가 아직 `HELD`라면 좌석은 다른 사람에게 가지 않았으므로, 선점 시간 안에 승인된 결제는 소비가 늦어도 확정한다.
- `HELD` → `EXPIRED` 조건부 UPDATE가 0행이면(만료 스케줄러가 먼저 처리) 다시 읽어 바뀐 상태의 규칙을 적용한다.
- 같은 `paymentId`로 `RefundRequested`를 다시 내도 Payment는 결제당 환불 한 건으로 묶는다(0019). Reservation은 이를 믿고 중복 발행 방지를 따로 두지 않아도 된다.

### PaymentDeclined

| 예매 상태 | 처리 |
|---|---|
| `HELD`, `userId` 일치 | `RELEASED`, 좌석 반환, 매수 한도 감소 |
| `HELD`, `userId` 불일치 | 변경 없음, 즉시 알림 |
| `CONFIRMED`·`CANCELED` | 변경 없음, 즉시 알림(확정에 쓰인 결제는 거절될 수 없다) |
| `EXPIRED`·`RELEASED` | 변경 없음(기록) |

### PaymentRefunded

| 예매 상태 | 조건 | 처리 |
|---|---|---|
| `CANCELED` | `paymentId` = 예매의 `payment_id` | 환불 완료 기록 |
| `EXPIRED`·`RELEASED`·`HELD` | — | 기록(만료 뒤 승인·확정 불가 승인·금액 불일치 승인의 환불 완료) |
| `CONFIRMED` | `paymentId` = 예매의 `payment_id` | **자동 좌석 해제 금지.** 정상 취소 근거(취소 요청 기록)가 확인될 때까지 이상 상태로 격리하고 즉시 알림. Payment 대사 RC-3도 찾는다 |
| `CONFIRMED` | `paymentId` 다름 | 기록(중복 승인의 환불 완료) |

### 공통 규칙
- 한 트랜잭션에서 `processed_event`, 조건부 UPDATE, 예매 이력, Outbox(`RefundRequested`)를 쓴다. 조건부 UPDATE가 있는 처리는 영향 행이 1일 때만 Outbox를 쓴다(0021과 같은 규칙). 상태 변경 없이 환불만 요청하는 줄은 `processed_event`와 Outbox를 한 트랜잭션에 쓴다.
- "이상 격리"는 상태를 바꾸지 않고 이벤트와 사유를 기록해 사람이 보게 하는 것이다. 저장 위치(예: 이상 기록 테이블 또는 Reservation 쪽 `dead_letter`)와 알림 지표 이름은 반효승이 정한다.
- 소비 실패가 3회 재시도 뒤 DLQ로 가면 Reservation의 `payment.result` DLQ 처리기가 이어받는다(0021의 통보 항목).

### 이벤트 필드
- `PaymentApproved`·`PaymentRefunded`에 `currency`(`"KRW"`)를 더한다. SA 4-5가 원화만 다루므로 값은 고정이지만, PG 결과·예매와 명시적으로 대조할 수 있고 단위 오류를 조용히 넘기지 않는다. Reservation은 별도 컬럼 없이 상수 `KRW`와 비교한다.
- `RefundRequested`는 `reservationId, paymentId, userId, amount, currency, reason`이다. `amount`는 환불 대상 결제의 금액(승인 이벤트 값)이다. `reason`은 `EXPIRED_AFTER_APPROVAL`·`CANCELED`·`DUPLICATE_APPROVAL`·`NOT_CONFIRMABLE`이다. Payment는 이 값을 결제 행과 교차 검증한다(0019).
- SA 10-4는 필드 추가만 허용하므로 `version`은 1로 유지한다.

## 검토한 대안

- **0015 원안(`HELD`가 아니면 환불)**: 단순하다. 재전달 때 확정 티켓의 결제가 환불된다(C1).
- **이벤트를 믿고 대조하지 않음**: 소비자가 단순하다. 버그·위조 이벤트로 다른 금액·다른 사용자의 승인이 확정된다(H2).
- **Reservation이 확정 전에 Payment를 동기 조회해 확인**: 가장 확실하다. 서비스 사이 동기 결합과 Payment 장애 전파가 생기고 SA의 Choreography 결정(#24)과 어긋난다. 이벤트 값 대조와 Payment 쪽 대사로 대신한다.

## 트레이드오프

| | 얻는 것 | 감수하는 것 |
|---|---|---|
| paymentId 대조와 멱등 처리 | 재전달이 확정 티켓을 환불하지 않는다 | `reservation.payment_id`를 확정 때 반드시 채워야 한다 |
| 원장 대조 | 금액·사용자가 다른 승인은 확정되지 않고 환불된다 | 소비자 코드와 테스트가 늘어난다 |
| `CONFIRMED` + 환불 격리 | 잘못된 환불 이벤트가 좌석을 풀어 이중 판매로 번지지 않는다 | 사람이 확인할 때까지 좌석과 돈이 어긋난 채 남는다(알림·대사로 추적) |
| `currency` 필드 | 단위 대조가 명시적이다 | 이벤트 세 개의 형식 변경 |

## 결과

- 반효승과 협의한다. 합의 전까지 이 ADR은 제안이고, SA 변경 요청 S-1·S-17·S-18로 올린다.
- Payment 쪽: `RefundRequested` 교차 검증(0019), 대사 RC-1~RC-4(0028).
- 테스트(반효승 담당 코드): N2, N22. Payment 쪽 통합 시나리오: E8, FT-1의 V3.

## 면접에서 설명할 한 줄

결제 결과 이벤트를 "왔으니 반영"하지 않고 예매 원장과 paymentId·금액·사용자를 대조해 상태별로 처리하게 해서, 재전달은 멱등으로, 중복 승인은 환불로, 근거 없는 환불은 격리로 끝나게 했다.
