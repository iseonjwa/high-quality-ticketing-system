---
title: 결제 서비스 구현 완료는 M3까지인가 M4까지인가
date: 2026-10-29
updated: 2026-10-29
asker: Junhyun An
level: 기초
tags: [scope, milestone, payment, moscow]
---

# 결제 서비스 구현 완료는 M3까지인가 M4까지인가

## 질문
결제 서비스를 "구현 완료"라고 하려면 M4 범위까지 전부 해야 하는가. 설계상 M3 범위까지 하면 결제가 완성됐다고 말할 수 있어 보인다.

## 짧은 답
M3까지가 "결제 MVP 완성"이고, M4 범위는 Should로 분류된 추가 기능이다. SA 5-1의 Must(결제 승인, 멱등·금액 검증, 타임아웃·CB, 만료 뒤 승인 환불, Outbox 2경로)가 M3 안에 다 들어 있다. 다만 "결제 구현 완료"라는 말은 범위를 붙여 써야 정확하다. "결제 MVP(Must) 완료"는 M3까지로 말할 수 있고, M4 항목은 아직 하지 않았다고 따로 밝힌다.

## 자세히
| 구분 | 결제 쪽 항목 | 근거 |
|---|---|---|
| M2 | 일반 예매 한 흐름(승인 → `payment.result`) | SA 5-2 |
| M3 | 실패 경로(거절·만료·PG 장애·재확인 스케줄러), Mock PG 장애 주입, 환불 워커·DLQ·대사·Ops 명령 테이블(`V3__refund_and_recovery.sql`) | SA 5-2, `data-model.md` |
| M4 | 토스 테스트 모드 어댑터(`TossPgAdapter`), 빌링키(`V4__billing_key.sql`, `BillingPaymentRequested`), 취소 환불 | SA 5-1 Should, 6장 분담표, `pg-integration.md` |

- SA 5-1에서 토스 어댑터·취소 환불·빌링키는 Must가 아니라 Should다.
- SA 5-2의 M4 줄에 "장애 주입·대사 정리"가 있다. 결제 설계에서는 대사 테이블과 기능이 M3 구현이고, M4는 이를 다듬고 정리하는 단계로 읽는다. 팀 합의로 확인한 것은 아니다.

## 이 프로젝트에서는
- 결제 설계 문서의 `M3` 표시(`data-model.md`, `contracts.md`의 `PaymentRefunded`)와 `M4` 표시(토스, 빌링키)가 같은 기준으로 나뉘어 있다.
- 11/6 MVP 시연 범위는 M3까지다. 발표에서는 "MVP 결제 완료"와 "M4 확장 계획"을 구분해 말한다.

## 면접 포인트
- 완료의 정의를 먼저 정했는가: 우선순위(MoSCoW)로 범위를 자르고, 안 한 것은 안 했다고 말하는 것이 신뢰를 준다.
- 구현 완료와 검증 완료는 다르다. M3 완료의 근거로 FT-1~FT-5와 V1~V3 실행 결과(`failure-scenarios.md` 4절)를 PR에 남긴다.

## 참고
- `docs/core/SA MASTER.md` 5-1 MoSCoW, 5-2 일정, 6장 기능 분담
- `docs/design/payment/data-model.md`, `docs/design/payment/pg-integration.md`
