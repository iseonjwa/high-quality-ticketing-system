---
title: L1~L3 ID 모드에서 일반 판매 회차를 SALE_SCHEDULE_ID로 나누고 회차 ID만 주면 실패하게 둠
date: 2026-11-09
updated: 2026-11-09
judge: kangmin
ai: Claude Code (k6 L1~L3 ID 입력 마무리 제안)
verdict: 수용
area: 측정 도구
presentation: 아님
shared: 예
---

# L1~L3 ID 모드에서 일반 판매 회차를 SALE_SCHEDULE_ID로 나누고 회차 ID만 주면 실패하게 둠

## 맥락
- 해결하려던 작업: 배포 측정(ISE-216 1안)을 위해 L1~L3이 서버에서 만든 추첨 ID를 받게 고쳤다. 작업자가 남긴 결정 2개와 범위 밖 일(`run.sh`, README)이 남아 있었다.
- AI 활용 방식: herdr 작업자가 스크립트를 고치고, 앱 세션이 남은 결정의 선택지와 추천을 냈다.

## AI 제안
- A1: L2-b·L3의 일반 판매 회차를 `SCHEDULE_ID` 대신 `SALE_SCHEDULE_ID`로 나눈다. Q1~Q3에서 `SCHEDULE_ID`는 "측정 대상 회차"라 뜻이 갈리고, 측정 날 잘못 넣으면 측정 1회를 버린다.
- B1: `LOTTERY_ID` 없이 회차 ID만 주면 실패시킨다. 모자란 부분을 관리자 로그인으로 채우면 발생기에 관리자 비밀번호가 다시 필요해진다.
- `run.sh`의 `PASS_ENV`에 두 변수를 더한다. 빠지면 `run.sh`로 돌릴 때 ID가 k6에 전달되지 않아 관리자 로그인 흐름으로 간다.

## 판단
- 결과: 수용
- 판단자: kangmin
- 근거: "추천안대로 진행"
- 검토한 대안: A2(`SCHEDULE_ID`를 시나리오마다 다른 뜻으로 쓰기), B2(모자란 부분만 관리자 로그인으로 채우기)

## 반영
- 바뀐 파일:
  - `loadtest/k6/lib/lottery.js`의 `preparedLottery`
  - `loadtest/k6/scenarios/l2-lottery-billing.js`, `l3-lottery-interference.js`(주석)
  - `loadtest/k6/run.sh`(`PASS_ENV`)
  - `loadtest/k6/README.md`(변수 표, 관리자 계정 문단, L1~L3 추첨 준비 표)
- 커밋은 AWS 담당이 ISE-216에 답한 뒤에 한다.

## 검증
- 이 PC에 k6가 없어 VS Code 내장 Node(v24.22.1)로 검증했다. k6 모듈을 대신하는 스텁을 붙였다.
  - 문법 검사: `lib/*.js`, `scenarios/l*.js` 모두 통과
  - 입력 조합 12개(없음, 회차 ID만, 일치·불일치, L1에 판매 회차, L3 판매 회차 누락·중복, 옛 방식, DRAWN 상태): 모두 기대대로
- 변이 테스트:
  - "L1에 판매 회차를 주면 실패" 검사를 끄면 1건 실패
  - "판매 회차 = 추첨 회차면 실패" 검사를 끄면 1건 실패
  - 일부러 틀린 문법 파일은 SyntaxError로 실패
- 확인 못 함: 실제 k6 실행(`k6 inspect`), 서버 추첨 조회 응답의 시각 형식. 둘 다 발생기에서 확인한다.

## 판단 이력
- 2026-11-09 수용: 추천안(A1, B1)대로 진행했다.

## 발표·면접 포인트
- 측정 도구의 입력 이름은 "측정 날 실수 비용"으로 정했다. 측정 1회를 다시 하는 비용이 변수 하나 더 두는 비용보다 훨씬 크다.
