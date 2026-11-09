---
title: 토스 어댑터의 orderId를 서버가 토큰에서 만들고, 늦은 승인 방지는 Prevented를 내지 않으며, 빌링은 지원하지 않기로 한 AI 추천안을 수용함
date: 2026-11-09
updated: 2026-11-09
judge: Junhyun An
ai: Claude Code
verdict: 수용
area: 결제
presentation: 아님
shared: 예
---

# 토스 어댑터의 orderId를 서버가 토큰에서 만들고, 늦은 승인 방지는 Prevented를 내지 않으며, 빌링은 지원하지 않기로 한 AI 추천안을 수용함

## 맥락
- 해결하려던 작업: ISE-45. 토스페이먼츠 어댑터를 실제 테스트 키(11/11 수령) 없이 가짜 토스 서버로 끝까지 만들기.
- AI 활용 방식: Claude Code가 ADR 0023·pg-integration.md의 미정 항목(토스 `orderId` 사전 발급 방식, 망취소 없는 토스의 `preventLateApproval`, 빌링 범위, 배포 기본 모드)에 추천안을 냈고 사용자가 받아들였다.

## AI 제안
1. `orderId`는 서버가 `PgOrderIds.of(reservationId, token.exp)`로 이름 기반 UUID를 만든다. 저장 없이 결제창 전(checkout API)과 TX1이 같은 값을 얻는다. TX1은 모드와 상관없이 쓰고 빌링 경로는 무작위로 둔다.
2. 공통 계약 테스트에 "늦은 승인 방지를 보장하는지" 훅(기본 true)을 둔다. 토스는 false라 `Prevented` 대신 `Unknown`을 기대한다.
3. 토스의 `preventLateApproval`은 이번 주에 `Prevented`를 내지 않는다. `DONE`이면 `AlreadyApproved`, 나머지는 `Unknown`이다.
4. 빌링은 토스 모드에서 지원하지 않는다. `issueBillingKey`는 `Unknown(CLIENT_ERROR)`, `approveBilling`은 `Declined(NOT_SENT)`다.
5. 배포 기본은 mock이고 토스는 시연 때만 켠다.

## 판단
- 결과: 수용
- 판단자: Junhyun An
- 근거: 미기재. 작업 지시에 "사용자 결정(2026-11-09, 추천안)"으로 적혀 있다.
- 검토한 대안: `orderId`를 DB에 저장하는 안(결제창 전에 행을 만들어야 해 TX1 전 쓰기가 늘어난다), 토스 취소 API로 `Prevented`를 흉내 내는 안(승인 여부가 불확실할 때 거짓 보장이 된다). 두 안은 이 기록의 근거가 아니라 설계 문서에 적힌 대안이다.

## 반영
- [ADR 0023](../../decisions/0023-payment-pg-client-port.md) 2026-11-09 개정 절, [pg-integration.md](../../design/payment/pg-integration.md) 3·6·7절, [api.md](../../design/payment/api.md) 1-1절, [deployment.md](../../design/infra/deployment.md) "결제 PG 모드 전환"
- 코드: `TossPgAdapter`, `PgOrderIds`, `PgCheckoutProfile`, `POST /api/v1/payments/checkout` (브랜치 `feat/ISE-45-toss-pg-adapter`)

## 검증
- `./gradlew --max-workers=4 :payment-service:test` 전체 통과(830개). 토스 계약 테스트가 공통 계약을 가짜 토스 서버로 통과하고 늦은 승인 방지만 `Unknown`을 기대한다.
- 실제 토스 호출은 하지 않았다. 11/11 이후 `tossSandboxTest`와 시연으로 확인한다.

## 판단 이력
- 2026-11-09 수용: 위 다섯 가지를 받아들이고 구현했다.
