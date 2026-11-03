---
title: 대사 RC-5(예매 금액)·RC-6(사용자) 불일치를 APPROVED 결제로만 보도록 ADR 0029를 고침
date: 2026-11-03
updated: 2026-11-03
judge: junhyun
ai: Claude Code(메인 Opus) + Opus 리뷰(deep-reasoner)
verdict: 수용
area: 결제
presentation: 아님
shared: 예
---

# 대사 RC-5(예매 금액)·RC-6(사용자) 불일치를 APPROVED 결제로만 보도록 ADR 0029를 고침

## 맥락
- 해결하려던 작업: ISE-168(예매와 대조하는 대사 규칙 RC-1~RC-6) 구현 뒤 리뷰.
- AI 활용 방식: 메인이 Opus 리뷰어에게 규칙 정확성과 `OPEN`·`RESOLVED` 안전성을 검토하게 했다. 리뷰어가 RC-5·RC-6 `OPEN` 행이 구조상 닫히지 않는다는 문제를 찾았고, 메인이 선택지 셋을 사용자에게 물었다.

## AI 제안
- 문제: ADR 0029 규칙표는 RC-5(결제 금액 ≠ 예매 총액)와 RC-6(결제 사용자 ≠ 예매 사용자)을 `APPROVED`·`REFUNDED` 결제 모두에 적용한다. 비교하는 네 값은 양쪽 모두 바뀌지 않으므로 한 번 잡히면 환불로 처리해도 같은 판정이 반복된다. 닫는 명령도 없어 즉시 알림이 영원히 울린다.
- 선택지
  1. (추천) RC-5의 예매 금액 비교와 RC-6을 `APPROVED`만 본다. 확인 뒤 환불하면 `REFUNDED`가 되어 다음 대사에서 닫힌다. 환불 금액 ≠ PG 승인 금액 검사는 `REFUNDED`에 그대로 둔다.
  2. ADR 그대로 두고 운영자가 확인 처리(ack)하는 Ops 명령을 새로 만든다(4-eyes·감사 기록, 작업량 큼).
  3. ADR 그대로 두고 한계와 직접 SQL 절차만 문서에 적는다.

## 판단
- 결과: 수용(선택지 1)
- 판단자: junhyun
- 근거: 미기재
- 검토한 대안: 확인 처리 Ops 명령(2), 문서만 고침(3)

## 반영
- `AmountMismatchRule`·`UserMismatchRule`(ISE-168, PR #284), ADR 0029 규칙표 갱신, `events-and-reliability.md` 10절, `manual-recovery.md` 6절.

## 검증
- 테스트: `REFUNDED` 결제의 예매 금액·사용자 불일치는 잡지 않음, 열린 RC-5·RC-6 행은 결제가 `REFUNDED`가 되면 다음 회차에 `RESOLVED`, 환불 금액 검사는 계속 잡음(PR #284).

## 판단 이력
- 2026-11-03 수용: RC-5 예매 금액 비교와 RC-6을 `APPROVED`로 한정

## 발표·면접 포인트
- 바뀌지 않는 값을 비교하는 감시 규칙은 "처리하면 닫히는 경로"가 없으면 영구 알림이 된다. 규칙의 대상 범위를 처리 결과(환불)로 빠져나갈 수 있게 정해 알림이 행동으로 끝나게 했다.
