---
title: 패키지 분리 PR의 base를 ISE-184 브랜치로 둔 구성을 거부하고 dev로 다시 올림
date: 2026-11-05
updated: 2026-11-05
judge: kangmin
ai: Claude Code
verdict: 거부
area: 협업
presentation: 아님
shared: 아니오
---

# 패키지 분리 PR의 base를 ISE-184 브랜치로 둔 구성을 거부하고 dev로 다시 올림

## 맥락
- 해결하려던 작업: 추첨 리팩토링(ISE-184)과 domain 패키지 분리(ISE-190)를 이슈별 PR로 나눠 올렸다.
- AI 활용 방식: 이슈 단위 정리와 커밋·push·PR 진행을 Claude Code에 맡겼다.

## AI 제안
이동 diff만 보이도록 ISE-190 PR(`#341`)의 base를 ISE-184 브랜치로 잡고, ISE-184 머지 뒤 base를 `dev`로 바꾸라고 안내했다. base를 바꾸지 않은 채 머지되어 패키지 분리가 ISE-184 브랜치에만 들어가고 `dev`에는 들어가지 않았다. Linear의 ISE-190은 머지로 Done이 됐다.

## 판단
- 결과: 거부
- 판단자: kangmin
- 근거: "dev에 해야지"
- 검토한 대안: `dev`에서 새 브랜치를 만들어 이동 스크립트를 다시 실행하는 방법

## 반영
ISE-190 브랜치에 `origin/dev`를 병합하고 `dev`를 base로 PR을 다시 열었다.

## 검증
- 병합 전 `git diff 475b337 origin/dev`가 비어 있어 `dev`의 내용이 검증한 ISE-184 커밋과 같음을 확인했다.
- 병합 뒤 `reservation-service` 테스트 결과는 해당 PR 본문에 적었다.

## 판단 이력
- 2026-11-05 거부: base를 ISE-184 브랜치로 둔 구성 대신 `dev`로 올리기로 했다.

## 발표·면접 포인트
- 쌓아 올린 PR은 diff를 줄여 주지만 base를 바꾸지 않고 머지하면 변경이 통합 브랜치에 들어가지 않는다. 머지된 PR이 곧 통합 완료를 뜻하는지는 대상 브랜치의 내용으로 확인해야 한다.
