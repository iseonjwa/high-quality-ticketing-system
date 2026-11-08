---
title: JUnit 부하 근사 대신 k6로 부하 테스트를 다시 하기로 하고, 대기열 초당 100명 입장 기준 병목·단계별 개선 계획 작성
date: 2026-11-04
updated: 2026-11-04
judge: 최권우
ai: Claude Code
verdict: 거부
area: 동시성
presentation: 후보
shared: 예
---

# JUnit 부하 근사 대신 k6로 부하 테스트를 다시 하기로 하고, 대기열 초당 100명 입장 기준 병목·단계별 개선 계획 작성

## 맥락
- 해결하려던 작업: ISE-79 좌석 선점 부하 측정. 2026-11-03에 워크트리에서 잰 부하 결과를 본 프로젝트로 옮기는 중이었다.
- AI 활용 방식: 워크트리에서 세팅한 k6를 본 프로젝트에도 세팅해 달라고 요청했다. AI는 워크트리에 k6가 없었고, 부하 측정은 JUnit 테스트 `SeatHoldLoadTest`(같은 JVM의 가상 스레드로 HTTP 부하, Gateway 미경유)로 했다고 답했다. 그 테스트를 본 프로젝트로 옮겼다.

## AI 제안
- 부하 측정 도구로 JUnit 근사 `SeatHoldLoadTest`를 쓴다. 단계(S0~S4)와 풀 크기 전후 비교용이며 SA 3-1의 공식 측정을 대신하지 않는다([concurrency-results.md](../../design/reservation/concurrency-results.md) 1절).
- k6 같은 실제 부하 도구 선택은 ISE-25 담당과 정한다([load-test-scenarios.md](../../design/reservation/load-test-scenarios.md)).

## 판단
- 결과: 거부
- 판단자: 최권우
- 근거: 사용자는 "테스트코드가 아닌 k6를 이용해서 부하테스트를 다시 진행할 예정"이라고 했다. 대기열에서 들어오는 동시 사용자 100명이 1분간 1초 간격으로 들어오는 상황을 가정하라고 했다.
- 검토한 대안: JUnit 근사 유지(같은 JVM이라 부하 생성기와 앱이 CPU를 나눠 쓰고 Gateway를 거치지 않는다)

## 반영
- [k6-seathold-bottleneck-plan.md](../../design/reservation/k6-seathold-bottleneck-plan.md): 시나리오 K1(열린 모델, 초당 100명 × 60초), 예상 병목 B1~B7, 개선 단계 P0~P7, 단계별 트레이드오프.
- `SeatHoldLoadTest`는 본 프로젝트에 남아 있다. 지울지는 정하지 않았다.

## 검증
측정 전이다. 병목 순위는 코드 읽기와 2026-11-03 JUnit 근사 결과에 근거한 가설이다.

## 판단 이력
- 2026-11-04 거부: JUnit 근사 대신 k6로 다시 재기로 하고 병목·개선 계획 문서를 요청했다.

## 발표·면접 포인트
- 같은 JVM 안에서 부하를 주는 근사 측정은 단계 전후 비교에는 쓸 수 있다. 하지만 Gateway 구간과 부하 생성기의 CPU 간섭을 가려서 실제 병목 순위를 말하기 어렵다. 그래서 k6로 실제 경로를 다시 재기로 했다.
