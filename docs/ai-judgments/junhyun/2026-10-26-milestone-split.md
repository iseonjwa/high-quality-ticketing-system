---
title: 개발 일정 마일스톤: M1은 직접 정하고 M2–M4는 AI 분할안 수용
date: 2026-10-26
updated: 2026-10-26
judge: junhyun
ai: Claude Code (Notion SA MASTER v3 5-2 일정·6장 분담을 읽고 분할)
verdict: 수용
area: 범위·일정
presentation: 아님
shared: 예
---

# 개발 일정 마일스톤: M1은 직접 정하고 M2–M4는 AI 분할안 수용

## 맥락
- 해결하려던 작업: Notion 「프로젝트 일정관리」 DB에 개발 마일스톤을 만드는 일
- AI 활용 방식: 첫 단계(공통 모듈 + 인증, 공통 모듈은 각자 만든 뒤 장점을 뽑아 하나의 브랜치로 합침)는 직접 정해 주고, 다음 마일스톤은 SA MASTER v3를 근거로 대략 나눠 달라고 했다

## AI 제안
SA 5-2 일정과 6장 담당을 따라 네 단계로 나눴다. M1 공통 모듈 + 인증 기반(10/27–10/29), M2 일반 예매 한 흐름 연결 + 동기형 기준선 측정(10/30–11/3), M3 실패 경로·배포 1회·MVP 시연(11/4–11/6), M4 고도화(11/9–11/16, 접수형 재측정·추첨·취소·캐싱·최소 화면). SA 6-2에서 10/29 마감인 docker-compose, Gateway·Eureka, 운영자 시드도 M1에 넣었다. 전문은 [`docs/core/milestones.md`](../../core/milestones.md)에 있다.

## 판단
- 결과: 수용
- 판단자: junhyun
- 근거: 미기재 (Notion 생성 결과를 받고 "docs 에 core/ 에도 넣어놔줘"라고 요청)
- 검토한 대안: 없음

## 반영
- Notion 「프로젝트 일정관리」 DB에 M1–M4 행 4개 (Select 로드맵)
- [`docs/core/milestones.md`](../../core/milestones.md)

## 검증
구현 전

## 판단 이력
- 2026-10-26 수용: Notion 마일스톤 4개를 저장소 `docs/core/`에도 두기로 했다

## 발표·면접 포인트
- 일정 분할은 AI에 맡기되 첫 단계의 협업 방식(공통 모듈을 각자 만들고 장점을 합침)은 팀원이 직접 정했다.
