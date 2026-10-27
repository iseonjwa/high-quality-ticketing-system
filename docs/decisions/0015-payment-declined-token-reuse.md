# 0015. 거절된 결제의 토큰 재사용은 Payment와 Reservation이 함께 막는다

- 상태: 제안 (SA 변경 요청과 반효승 협의 필요. 2026-10-28 개정)
- 날짜: 2026-10-27
- 결정자: 안준현
- 관련 AI 판단: [결제 상세 설계 빈틈 결정](../ai-judgments/junhyun/2026-10-27-payment-design-gaps.md)
- 관련 결정: [0018](0018-payment-new-key-existing-payment.md), [0025](0025-payment-one-per-reservation.md), [0026](0026-reservation-payment-event-contract.md)
- 불변 조건: INV-1, INV-4([invariants.md](../design/payment/invariants.md))

## 맥락

SA 7-3의 결제 토큰은 `exp`(선점 만료 − 10초)까지 유효하고 `jti` 폐기 목록은 만들지 않는다. SA 12-4의 D3 부분 유니크(`UNIQUE(reservation_id) WHERE status IN (...)`)는 DECLINED를 포함하지 않는다.

그래서 결제가 DECLINED되면 Reservation이 예매를 RELEASED로 바꾸고 좌석을 반환하지만, 사용자가 같은 토큰으로 다시 승인 요청을 보내면 Payment는 새 결제를 받아 PG에 보낼 수 있다. 승인되면 이미 풀려 다른 사람이 선점했을 수 있는 좌석에 결제가 성립한다. SA 8-3에는 `EXPIRED`에 PaymentApproved가 도착하는 경우(E8)만 있고 `RELEASED`에 도착하는 경우의 규칙이 없다.

## 결정

두 겹으로 막는다.

1. **Payment**: 같은 `reservation_id`에 `DECLINED`·`REFUNDED` 결제가 있으면 새 승인 요청을 409 `PAYMENT_ALREADY_CLOSED`로 거절하고 PG를 호출하지 않는다(`REFUNDED`는 만료 뒤 승인·취소로 환불된 예매에 토큰이 아직 유효한 경우를 막는다. [0018](0018-payment-new-key-existing-payment.md) 작성 중에 추가). 이 조회는 빠른 응답용이다. 조회와 쓰기 사이의 경합은 `UNIQUE(reservation_id)` 전체([0025](0025-payment-one-per-reservation.md))가 막는다.
2. **Reservation**: 확정할 수 없는 승인(예매가 `EXPIRED`·`RELEASED`이거나, 원장과 사용자·금액·통화가 다름)은 확정하지 않고 `RefundRequested`를 낸다. 단 같은 `paymentId`로 이미 `CONFIRMED`·`CANCELED`인 예매에 온 승인은 재전달이므로 아무것도 하지 않는다. 상태별 규칙은 [0026](0026-reservation-payment-event-contract.md)이 정한다.

## 검토한 대안

- **Reservation 환불만 둔다**: Payment 쪽 변경이 없어 가장 작다. 대신 거절된 예매로 승인과 환불이 불필요하게 일어나고, 환불이 PG에서 실패하면 돈이 묶인다. 사고가 났을 때 수습하는 방식이라 채택하지 않았다.
- **토큰 `jti` 폐기 목록**: 토큰을 1회용으로 만든다. SA 7-3이 단순함을 이유로 정한 결정을 되돌리고, UNKNOWN 중 재시도(같은 토큰으로 상태 확인)와 충돌해 사용자 경험이 나빠질 수 있어 채택하지 않았다.
- **Payment 조회만으로 막기(2026-10-27 원안의 구현)**: 조회 시점에 상대 결제가 커밋 전이면 보이지 않아 경합이 남는다(검토 H1). DB 제약으로 보완했다(0025).

## 트레이드오프

| | 얻는 것 | 감수하는 것 |
|---|---|---|
| Payment 차단(조회 + UNIQUE) | 불필요한 PG 호출과 환불을 아예 막고, 거절 건은 사용자가 바로 안다. 경합도 DB가 막는다 | 결제 시작 때 DB 조회 1회. 거절 뒤 같은 예매로 재시도하는 흐름은 불가(예매가 이미 풀려 있어 원래 불가) |
| Reservation 환불 | Payment 차단이 놓친 경우와 UNKNOWN이 나중에 APPROVED가 되는 경우도 보상한다 | 반효승 담당 소비자의 규칙 변경, 환불 PG 호출 비용 |
| 두 겹 | 한쪽이 빠져도 돈이 남지 않는다 | 두 서비스의 규칙이 맞물려 합의가 필요하다 |

## 결과

- SA 변경 요청: S-1(8-3 상태별 규칙, 0026), S-2(`PAYMENT_ALREADY_CLOSED`), S-3·S-16(전체 UNIQUE, 0025)([sa-change-requests.md](../design/payment/sa-change-requests.md)).
- 반효승과 `PaymentApproved` 소비 규칙(0026)을 협의한다(SA 17-1 #1 계약 항목).
- 같은 예매의 기존 결제 조회는 `UNIQUE(reservation_id)` 인덱스를 쓴다. 이전에 후보로 둔 `INDEX(reservation_id, status)`는 필요 없다.
- 테스트: N1(DECLINED 뒤 재승인 409), N13(동시 요청 경합), N2·N22(Reservation 규칙).

## 개정 이력

- 2026-10-28: Opus 검토 지적과 사용자 결정을 반영했다. (1) 결정 2의 원안 "HELD가 아닌 모든 상태(EXPIRED·RELEASED·CANCELED 등)에 PaymentApproved가 오면 RefundRequested"는 `CONFIRMED`를 포함해, 재전달 때 확정된 티켓의 결제가 환불될 수 있었다(C1). 상태별 규칙을 0026으로 옮기고 같은 paymentId의 확정·취소 예매는 멱등 처리로 바꿨다. (2) 결정 1의 조회 후 쓰기 경합(H1)은 사용자 결정 1에 따라 0025의 전체 UNIQUE로 막는다. (3) 결과 항목의 `INDEX(reservation_id, status)` 후보를 없앴다.

## 면접에서 설명할 한 줄

토큰을 1회용으로 만드는 대신, 결제 서비스가 "이미 거절된 예매는 받지 않는다"를 DB 제약으로 지키고 예매 서비스가 "확정할 수 없는 승인은 환불하되 이미 확정한 결제의 재전달은 무시한다"를 지켜서 어느 한쪽이 뚫려도 돈이 남지 않게 했다.
