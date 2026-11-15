---
title: Synthetic O1 측정을 앞두고 dev → main을 먼저 하자는 AI 추천을 받지 않고 지금 배포된 main으로 측정하기로 함
date: 2026-11-14
updated: 2026-11-14
judge: kangmin
ai: Claude Code(메인 세션이 git diff로 main·dev 서버 차이를 정리하고 검증 에이전트가 대조)
verdict: 거부
area: 범위·일정
presentation: 아님
shared: 예
---

# 측정 전 dev → main 추천을 받지 않고 지금 main으로 측정

## 맥락
- 해결하려던 작업: 11/14 밤 O1 Synthetic 시험 실행과 11/15 ISE-264 측정(O1 균일·Synthetic, A0) 준비.
- main(`e109c11c`)과 dev(`3c56a7a7`)의 서버 차이(`git diff origin/main origin/dev`)
  - `#609`: 동기 선점 본체를 `SeatHolding`으로 옮김(`SyncSeatHoldService` +5/−78행). 접수형 소비자를 늘 켜고, 시한 정리 스케줄러가 모드와 상관없이 5초마다 DB 트랜잭션을 돈다.
  - `#620`: 입장 처리 주기마다 회차별 `stats.lua`.
  - 대기열 동작 파일은 같다.

## AI 제안
- 측정 전에 팀 동의를 받아 dev → main을 하고, 새 main에서 시험 실행으로 배포 확인과 리허설을 같이 하자(A안).
- 이유
  - 발표·시연에 쓸 코드로 잰다.
  - main≈dev가 되면 측정 중 실수로 머지가 들어와도 바뀌는 것이 적다.
- 대안 B: 지금 main으로 잰다. 안정적이지만 측정한 코드와 곧 배포될 코드가 달라 "main 반영 뒤 재측정"이 다시 생긴다.

## 판단
- 결과: 거부(B안 선택)
- 판단자: kangmin
- 근거: 미기재("지금 main으로 측정")
- 검토한 대안: A안(측정 전 dev → main)

## 반영
- `docs/context/o1-synthetic-pilot-2026-11-14.md`(git 제외) 머리말에 측정 기준 커밋을 main `e109c11c`로 적음.
- 측정 기록에는 서버 main 커밋과 발생기 dev 커밋을 따로 적는다.

## 검증
- 측정 전.

## 판단 이력
- 2026-11-14 거부: 지금 main으로 측정.

## 발표·면접 포인트
- 측정 결과는 `#609`(동기 선점 리팩터링, 시한 정리 스케줄러) 전 코드 기준이다. dev → main 뒤에 같은 시나리오를 다시 재면 그 변경의 영향을 따로 볼 수 있다.
- 측정 중 main 머지 금지(D-5)는 main≠dev라 여전히 중요하다.
