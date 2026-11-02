---
title: 실제 PG(토스페이먼츠)를 붙여도 Mock PG가 필요한가
date: 2026-11-02
updated: 2026-11-02
asker: Junhyun An
level: 중급
tags: [payment, pg, mock-pg, toss-payments, contract-test, fault-injection, load-test]
---

# 실제 PG(토스페이먼츠)를 붙여도 Mock PG가 필요한가

## 질문
토스페이먼츠를 붙일 거면 Mock PG가 필요한가. Mock PG 장애 주입을 구현하던 중에 물었다.

## 짧은 답
필요하다. 둘은 서로를 대신하지 않고 쓰임이 다르다.
- 실제 PG 테스트 모드: 실제 결제창 흐름과 실제 응답 모양을 확인한다.
- Mock PG: 서버만으로 도는 결제, 부하 측정, 원하는 장애 재현, 호출 횟수 검증, CI를 맡는다.

그래서 결제 서비스는 PG 포트 하나에 어댑터 두 개를 두고 설정으로 바꾼다. 같은 계약 테스트를 두 어댑터에 돌려 둘이 같은 의미로 동작하는지 확인한다.

## 자세히

| 쓰임 | 토스 테스트 모드 | Mock PG |
|---|---|---|
| 실제 결제창 흐름(카드 입력 → 인증 → 승인) | 된다 | 안 된다 |
| 서버만으로 승인 | 안 된다. 구매자가 결제창에서 인증해야 `paymentKey`가 나오고, 서버는 그 값으로 승인 API를 부른다 | 된다. 주문 식별자와 금액만으로 승인한다 |
| 부하 측정(수천 건) | 부적절하다. 외부 테스트 서버에 부하를 거는 셈이고, 결과가 외부 네트워크에 좌우된다 | 된다. 지연을 로컬에서 통제한다 |
| 에러 응답 재현 | `TossPayments-Test-Code` 헤더로 에러 객체를 재현한다 | 장애 주입 모드로 만든다 |
| 네트워크 수준 장애(승인은 됐는데 응답만 끊김, 원하는 길이의 지연, 처리 중에 취소가 끼어듦) | 이런 기능은 공식 문서에서 찾지 못했다(미확인) | `DROP_RESPONSE`·`DELAY`·`IN_FLIGHT` 상태로 정확히 만든다 |
| 중복 검사("주문당 승인 호출 1회") | 우리가 몇 번 불렀는지 셀 수 없다 | 원장이 호출 수를 센다 |
| CI | 외부 의존과 시크릿 키가 생긴다 | 키 없이 로컬에서 돈다 |

PG 연동에서 가장 위험한 상황은 결과를 모르는 상태다. 승인 요청을 보냈는데 응답이 끊긴 경우가 대표적이다. 이때 실패로 닫으면 돈은 빠졌는데 예매는 실패한 상태가 된다. 이 경로를 테스트하려면 "PG는 승인했고 응답만 사라진" 상황을 원할 때 정확히 만들 수 있어야 한다. 실제 PG의 테스트 모드는 보통 에러 응답은 흉내 내지만 이런 상황은 만들어 주지 않는다.

## 이 프로젝트에서는
- `PgClient` 포트와 `MockPgAdapter`·`TossPgAdapter`(M4, ISE-45)가 있다. `payment.pg.provider`로 고른다(ADR 0023, `docs/design/payment/pg-integration.md`).
- 공통 계약 테스트 `PgClientContractTest`를 두 어댑터에 돌린다. Mock은 실제 mock-pg 앱에, 토스는 HTTP 스텁과 CI 밖의 테스트 환경 통합 테스트에 돌린다.
- M3 완료 기준("장애 주입으로 거절·타임아웃·만료 뒤 승인 환불 재현")과 V1 중복 검사는 Mock PG로 검증한다(`docs/core/milestones.md`, `docs/design/payment/failure-scenarios.md`).
- 일정상 토스 어댑터는 M4다. Mock PG가 없으면 M3까지 결제 흐름과 실패 경로를 검증할 PG가 없다.
- 시연 구성안: 정상 흐름은 토스 결제창, 실패 흐름과 부하 측정은 Mock PG(ISE-134).

## 면접 포인트
- "실제 PG를 쓰면 되는데 왜 Mock PG를 만들었나요?"
  - 실패 경로, 특히 결과를 모르는 상태를 원할 때 재현하고 검증하기 위해서다.
  - 부하 측정과 CI를 외부 의존 없이 돌리기 위해서다.
  - 대신 포트·어댑터 구조와 공통 계약 테스트로 Mock과 실제 PG가 같은 의미로 동작하는지 묶었다.
- "Mock이 실제 PG와 다르게 동작하면 어떻게 하나요?" 두 어댑터에 같은 계약 테스트를 돌린다. Mock 서버도 가짜 서버가 아니라 실제 앱을 띄워 검증한다. 차이가 나면 계약 테스트가 잡는다.

## 참고
- [토스페이먼츠 결제창 v2 연동](https://docs.tosspayments.com/guides/v2/payment-window/integration): 결제 요청 뒤 서버 승인
- [토스페이먼츠 환경 설정(테스트 코드 헤더)](https://docs.tosspayments.com/guides/v2/get-started/environment)
- ADR 0023 `docs/decisions/0023-payment-pg-client-port.md`
- 위 문서는 2026-11-02에 확인했다.
