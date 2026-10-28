# 0026. 결제 행은 예매당 하나인 논리적 결제로 두고 reservation_id 전체 UNIQUE로 강제한다

- 상태: 제안 (SA 변경 요청 필요, U9 결과에 따라 재검토)
- 날짜: 2026-10-28
- 결정자: 안준현(조건부 결정: "명확한 승인 거절 뒤 재시도 요구가 없으면 전체 UNIQUE". 조건 확인과 적용은 AI가 SA 근거로 했고 사용자 최종 확인 대기)
- 관련 AI 판단: [결제 상세 설계 빈틈 결정](../ai-judgments/junhyun/2026-10-27-payment-design-gaps.md)
- 관련 결정: [0016](0016-payment-declined-token-reuse.md), [0017](0017-payment-idempotency-in-progress.md), [0019](0019-payment-new-key-existing-payment.md), [0027](0027-reservation-payment-event-contract.md)
- 불변 조건: INV-1([invariants.md](../design/payment/invariants.md))

## 맥락

SA 12-4의 D3는 `UNIQUE(reservation_id) WHERE status IN ('REQUESTED','UNKNOWN','APPROVED')`(설명과 #58에는 `NEEDS_MANUAL_CHECK` 포함)다. `DECLINED`·`REFUNDED`가 빠져 있어 같은 예매에 결제 행이 여러 개 생길 수 있다. 그래서 결제 행은 사실상 "승인 시도" 단위였다.

0016는 `DECLINED`·`REFUNDED` 예매의 재승인을 조회로 막았다. 조회와 쓰기가 원자적이지 않아 다음 경합이 남는다(Opus 검토 H1).

1. 요청 R2(새 키, 같은 토큰)가 기존 결제를 조회한다. 요청 R1의 결제 P1은 아직 커밋 전이라 보이지 않는다.
2. P1이 TX1 커밋 → PG 즉시 거절 → TX2에서 `DECLINED`로 끝난다.
3. R2가 TX1에서 P2(`REQUESTED`)를 넣는다. 부분 유니크에 `DECLINED`가 없어 성공하고 PG를 부른다. 승인되면 이미 풀린 좌석에 결제가 성립한다.

사용자는 예매당 하나의 논리적 Payment를 유지하는 데 동의하고, 전체 UNIQUE 적용 전에 (a) 현재 결제 행이 논리적 결제 단위인지 승인 시도 단위인지, (b) SA의 M4 자동결제와 "명확한 승인 거절 뒤 재시도" 요구가 있는지를 SA·코드·설계 문서에서 직접 확인하라고 했다. 요구가 없으면 전체 UNIQUE, 있으면 Payment와 PaymentAttempt를 나누는 구조를 제안한다.

## 확인한 근거

| 확인한 곳 | 내용 | 판단 |
|---|---|---|
| SA 8-2·8-3 | 예매 `HELD → RELEASED (결제 거절)`, 전이 주체는 `PaymentDeclined` 소비자. 좌석 반환과 매수 한도 감소가 함께 일어난다 | 거절된 예매는 다시 결제할 수 없다 |
| SA 2-3, 2-5 E6, 2-6 | "미결제 만료·결제 거절 → 좌석 반환", E6 "예매 해제 + 좌석 반환 (보상)", 흐름도의 거절 → `RELEASED` | 같음 |
| SA 11-3 | `RESERVATION_NOT_PAYABLE`(409): `HELD`가 아닌 예매 | 같음 |
| SA 10-1 | "자동결제도 추첨 모듈이 당첨자 예매(HELD)를 먼저 만들고 그 reservationId로 요청한다. 결제는 일반 예매와 같은 키·같은 중복 결제 방지(D3)를 쓴다" | 자동결제도 예매당 결제 |
| SA 2-1, 2-3, 3장, 4-3, 8-2(응모) | 결제 실패 → "같은 조건 예비자 승계", 응모 `WON → PAID`·`WON → FAILED`, `WAITLISTED → WON (승계)` | 실패하면 같은 예매를 다시 결제하지 않고 다른 응모자에게 넘긴다 |
| SA 12-3 | `lottery_entry.reservation_id`는 "당첨 시 만든 예매", `reservation.user_id`는 NOT NULL | 승계자는 새 예매(새 `reservation_id`)를 받는다 |
| SA 15장 #58 | "RELEASED·DECLINED는 제외해야 좌석 재선택·재결제가 가능하다" | 재결제를 말하는 유일한 문장이다. 좌석 재선택은 새 예매라 전체 UNIQUE와 충돌하지 않는다. 같은 예매의 재결제는 8-3과 모순되므로 정정을 요청한다(S-16) |
| SA 4-2 ④ | 자동결제 요청 Kafka: "실패 건만 재시도" | 10-4·10-5의 소비 재시도 문맥으로 읽는다. 결제 거절 뒤 재승인 요구로 보지 않는다 |
| SA 부록 A M6, 17-2 U9 | "결제 실패 유형별 재시도·승계 … ⚠ 미해결", 담당 이강민, 추첨 시작 때 | 요구가 확정되지 않았다. 확정되면 이 ADR을 다시 연다 |
| 코드 | `payment-service`에는 `PaymentApplication`, `LocalIdentityController`, `ServiceConfiguration`, `LocalMessagingProbe`만 있고 결제 엔티티·마이그레이션이 없다 | 기존 데이터·코드 제약 없음 |
| 설계 문서 | 결제 행이 `pg_order_id` 하나를 갖고(UNIQUE), 재조회는 같은 주문을 조회하며, 환불은 결제당 1건(`refund.payment_id` UNIQUE) | 행은 논리적 결제이면서 PG 주문 하나 |

결론: SA v3.2에는 명확한 승인 거절 뒤 같은 예매를 다시 결제하는 요구가 없다. 사용자 조건에 따라 전체 UNIQUE를 적용한다. U9는 미결이라 재검토 조건으로 남긴다.

## 결정

1. 결제 행은 **예매당 하나인 논리적 결제**다. 결제 행 하나 = PG 주문(`pg_order_id`) 하나 = 승인 호출 1회다. 재조회는 같은 주문을 조회할 뿐 새 승인 시도를 만들지 않는다.
2. `payment`에 `UNIQUE(reservation_id)`를 상태와 무관하게 건다. D3 부분 유니크와 `INDEX(reservation_id, status)`를 대체한다.
3. TX1의 유니크 위반은 정상 흐름이다. 롤백하고 허가를 반환한 뒤 기존 결제를 다시 읽어 0019의 표로 응답한다(500 금지). 같은 키 충돌(멱등 기록 PK)은 0017의 현재 상태 응답으로 간다.
4. 0016의 조회(`DECLINED`·`REFUNDED`면 409 `PAYMENT_ALREADY_CLOSED`)는 빠른 응답과 명확한 에러 코드를 위해 유지한다. 조회와 쓰기 사이의 경합은 DB 제약이 막는다. 조회만으로 중복을 막는 로직은 남기지 않는다.
5. 거절·환불된 예매에 다시 결제하려면 좌석을 다시 선점해 새 예매로 결제한다.
6. U9가 "같은 당첨자의 같은 예매를 거절 뒤 다시 결제"를 요구하면 아래 대안 2(Payment/PaymentAttempt)로 바꾸는 ADR을 새로 쓴다. 다른 방법으로 같은 당첨자에게 새 예매를 만들어 주는 방식도 있어 이강민과 함께 고른다.

## 검토한 대안

- **D3 부분 유니크 + 조회 후 쓰기 유지**: 변경이 없다. 위 경합으로 토큰 재사용 차단이 뚫린다.
- **Payment와 PaymentAttempt 분리**: `payment`(논리, `UNIQUE(reservation_id)`)와 `payment_attempt`(`pg_order_id` UNIQUE, 시도 상태, 진행 중 시도 하나만 허용하는 부분 유니크)로 나눈다. 거절 뒤 재시도를 지원한다. 상태 머신이 둘이 되고, 이벤트·멱등 기록·재조회·환불이 어느 단위를 가리키는지 모두 정해야 한다. 지금 요구가 없어 채택하지 않았다. U9 결과에 따른 준비된 대안이다.
- **부분 유니크 + 예매 단위 잠금(advisory lock 또는 `SELECT … FOR UPDATE`)**: 경합을 막는다. 모든 쓰기 경로가 잠금을 잡아야 해서 한 곳만 빠져도 뚫린다. DB 제약은 경로와 무관하게 막는다.

## 트레이드오프

| | 얻는 것 | 감수하는 것 |
|---|---|---|
| 전체 UNIQUE | 조회·쓰기 경합과 토큰 재사용이 구조적으로 막힌다. PG 주문과 결제가 1:1이라 대사·환불·운영이 단순하다 | 거절된 예매로 다시 결제하는 흐름이 없다(SA 8-3상 원래 없음) |
| 결제 = 승인 호출 1회 | 토스 승인 멱등이 확인되지 않아도 같은 주문 재승인이 없다 | 서버가 PG 호출 전에 죽으면 그 예매는 `DECLINED(NOT_SENT)`로 끝나고 사용자는 다시 선점해야 한다 |
| 유니크 위반을 정상 흐름으로 | 동시 요청도 500 없이 기존 결제 응답을 받는다 | 제약 이름별로 예외를 나누는 코드가 필요하다 |

## 결과

- `data-model.md`: `UNIQUE(reservation_id)`, 부분 유니크·`INDEX(reservation_id, status)` 제거. `api.md`: 처리 순서 7단계의 유니크 위반 처리.
- 0016 개정(경합은 이 ADR이 막는다), 0017·0019 개정(TX1 충돌 재조회).
- SA 변경 요청 S-3·S-16, #58 정정(Notion 결정 기록 포함). U9 담당 이강민에게 이 결정과 재검토 조건을 알린다(S-15).
- 테스트: N1, N13, N14, 마이그레이션 테스트(인덱스 존재), FT의 V1.

## 면접에서 설명할 한 줄

"거절된 예매는 다시 결제받지 않는다"를 조회 코드가 아니라 `UNIQUE(reservation_id)` 하나로 지키게 해서 조회와 쓰기 사이 경합을 없앴고, 그 전에 SA의 상태 전이와 추첨 승계 규칙을 직접 대조해 "같은 예매 재결제" 요구가 없음을 확인했다.
