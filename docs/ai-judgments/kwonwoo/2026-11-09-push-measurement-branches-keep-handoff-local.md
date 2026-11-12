---
title: 측정 기기를 옮길 때 번들 대신 브랜치를 푸시하고, 세션 핸드오프 메모는 푸시하지 않고 직접 옮김
date: 2026-11-09
updated: 2026-11-09
judge: KWON-WOO
ai: Claude Code
verdict: 거부
area: 협업
presentation: 아님
shared: 예
---

# 측정 기기를 옮길 때 번들 대신 브랜치를 푸시하고, 세션 핸드오프 메모는 푸시하지 않고 직접 옮김

## 맥락
- 해결하려던 작업: ISE-79 k6 단계 측정을 이어 하던 노트북의 호스트 메모리가 부족해 base 재측정이 중간에 끊겼다. 측정을 다른 기기에서 이어 가려면 원격에 없는 브랜치(측정 브랜치 `experiment/ISE-79-k6-steps`, P4 측정 코드 `experiment/ISE-79-p4-measure`, feat 브랜치의 로컬 커밋 6개)를 옮겨야 했다.
- AI 활용 방식: 다른 기기로 옮기면 측정 결과가 부정확해지는지, 번들과 푸시가 어떻게 다른지 물었다.

## AI 제안
측정 브랜치는 머지하지 않는 실험 브랜치라 팀 브랜치 목록에 남길 이유가 없으므로 `git bundle`로 옮기자고 추천했다. feat 브랜치도 아직 PR을 낼 때가 아니니 번들에 함께 넣자고 했다. 두 기기를 오래 오가면 푸시가 낫다는 단서를 달았다([학습 노트](../../learning/kwonwoo/qna/2026-11-09-git-bundle-vs-push.md)).

## 판단
- 결과: 거부
- 판단자: KWON-WOO
- 근거: 과정이 번거롭지 않도록 푸시한다. 다만 세션 기록을 담은 핸드오프 메모(`HANDOFF-ISE-79-k6.md`)는 푸시하면 다른 팀원들의 작업에도 영향이 가므로 푸시하지 않고 갱신만 하고, 직접 옮긴다.
- 검토한 대안: 번들(추천안), 패치 파일

## 반영
- 원격에 푸시: `feat/ISE-79-seat-hold-race-condition`(기록 커밋 포함), `experiment/ISE-79-k6-steps`, `experiment/ISE-79-p4-measure`(커밋하지 않았던 P4c·P4d 측정 코드를 로컬 커밋한 뒤)
- 푸시하지 않음: `HANDOFF-ISE-79-k6.md`(저장소 루트, 커밋하지 않은 메모. 로컬 `.git/info/exclude`에 넣어 실수로 커밋되지 않게 함), 임시 재측정 브랜치(`experiment/ISE-79-p4a-remeasure`, `experiment/ISE-79-remeasure-net`)

## 검증
푸시 결과는 `git ls-remote --heads origin`으로 확인한다.

## 판단 이력
- 2026-11-09 거부: 번들 대신 푸시, 핸드오프만 직접 옮김

## 발표·면접 포인트
- 실험 브랜치를 팀 원격에 둘지는 "숨길 이유"보다 "옮기는 비용"으로 정했다. 대신 개인 세션 메모처럼 팀원 작업 흐름에 섞일 수 있는 파일은 원격에 올리지 않았다.
