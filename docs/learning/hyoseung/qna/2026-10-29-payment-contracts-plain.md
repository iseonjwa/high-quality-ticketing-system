---
title: 준현 님의 결제 계약 문서(contracts.md)는 무엇을 했고 무엇을 확인해 달라는 것인가
date: 2026-10-29
updated: 2026-10-29
asker: hyos1
level: 기초
tags: [contract, payment-token, jwt, kafka, saga, reservation]
---

# 준현 님의 결제 계약 문서(contracts.md)는 무엇을 했고 무엇을 확인해 달라는 것인가

## 질문
`docs/design/payment/contracts.md`를 읽어도 무엇을 했다는 것이고 무엇을 허락해야 하는지 모르겠다.

## 답
### 한 줄 요약
예매(Reservation, 내 담당)와 결제(Payment, 준현 님) 사이에 주고받는 것이 두 가지 있다. 준현 님은 Payment 쪽을 이미 구현했고, "내가 이 형식으로 받고 보낼 테니 Reservation도 이 형식에 맞춰 줄 수 있나"를 7가지로 묻고 있다.

### 주고받는 것 두 가지
```
사용자 ──① 좌석 선점──▶ Reservation ──결제 토큰 발급──▶ 사용자
사용자 ──② 결제 요청(결제 토큰 첨부)──▶ Payment  (토큰 검증 후 PG 승인)
Payment ──③ payment.result 이벤트(Kafka)──▶ Reservation  (확정·발권 또는 좌석 반환)
```
- **계약 ③ 결제 토큰**: Reservation이 만들어 주는 "결제 허가증"이다. 안에 누가(`sub`), 어느 예매를(`reservationId`), 얼마에(`amount`), 언제까지(`exp`) 결제할 수 있는지 적고 서명한다. Payment는 Reservation에 묻지 않고 서명만 확인해 믿는다. 영화관 매표소가 찍어 준 도장 있는 확인증을 결제 창구가 보는 것과 같다.
- **계약 ④ `payment.result`**: Payment가 결제 결과를 Kafka로 알려 주는 "결과 통보"다. 승인(`PaymentApproved`)이면 Reservation이 예매를 확정하고 티켓을 발급한다. 거절(`PaymentDeclined`)이면 좌석을 반환한다.

### 확인 요청 7가지를 쉬운 말로
| # | 묻는 것 | 의미 | 내 쪽에 생기는 일 |
|---|---|---|---|
| 1 | 토큰 안 항목 이름 | `sub`, `reservationId`, `amount` 등 이름과 숫자 형식 | 발급 코드에서 이 이름으로 넣는다 |
| 2 | 서명 방식과 키 | HS256, 키 `PAYMENT_TOKEN_SECRET`, 공통 함수 `TokenDecoders.signingKey` 사용 | 같은 키·같은 함수로 서명한다 |
| 3 | 토큰 만료 | 선점 만료 10초 전에 토큰도 만료 | `exp = hold_expires_at - 10초`로 계산해 넣는다 |
| 4 | 결제 ID 기록 | 예매 확정 때 `payment_id`를 저장하고 이벤트 값과 대조 | 소비자에서 저장·대조한다 |
| 5 | 이벤트 필드 사용 | 승인 이벤트의 `currency`·`approvedAt`을 쓴다 | `approvedAt`으로 "만료 뒤 승인"을 판단한다 |
| 6 | 환불 요청 | 만료된 예매에 승인이 오면 환불 요청 이벤트를 보낸다 | `payment.command`로 `RefundRequested` 발행 |
| 7 | 처리 실패 | 3번 실패한 이벤트를 어떻게 처리할지 Reservation이 정한다 | 나중에 정한다 |

모두 SA 7-3·10-3과 이미 합의된 ADR(0025, 0027)을 구현 수준으로 적은 것이다. 새로 바뀐 것은 이름 정리와 `currency` 필드 추가 정도다.

### 무엇을 하면 되나
지금 구현할 것은 없다. 토큰 발급과 이벤트 소비는 예매 상태 단계(ISE-63·64 다음)에서 만든다. 지금은 "이 형식에 맞춰 만들겠다"고 동의하면 계약이 확정되고, 준현 님이 SA를 고친다. 구현하다가 바꿀 것이 생기면 그때 다시 맞춘다.

## 핵심 정리
- 결제 토큰 = Reservation이 발급하는 결제 허가증, `payment.result` = Payment가 보내는 결과 통보.
- 확인 요청은 "그 형식에 맞춰 만들어 달라"는 것이고 지금 할 구현은 없다.

## 면접 포인트
- 서비스 간 계약을 먼저 고정하는 이유: 생산자와 소비자가 동시에 개발할 수 있고, 늦게 정하면 양쪽을 다 고쳐야 한다(SA U1).
- 결제 토큰을 서명된 JWT로 둔 이유: Payment가 결제마다 Reservation을 호출하지 않아도 금액·예매·사용자를 믿을 수 있고, 위조는 서명으로 막는다.

## 참고
- `docs/design/payment/contracts.md`, `docs/decisions/0025-*.md`, `docs/decisions/0027-*.md`
- `docs/core/SA MASTER.md` 7-3, 10-3
