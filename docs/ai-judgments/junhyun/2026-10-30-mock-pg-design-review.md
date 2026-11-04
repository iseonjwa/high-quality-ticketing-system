---
title: Mock PG 설계 Opus 리뷰 지적을 모두 반영하고 실행 방식·멱등 응답·compose 처리 4건을 결정함
date: 2026-10-30
updated: 2026-10-30
judge: junhyun
ai: Claude Code + Opus 리뷰 에이전트(deep-reasoner)
verdict: 수용
area: 결제
presentation: 후보
shared: 예
---

# Mock PG 설계 Opus 리뷰 지적을 모두 반영하고 실행 방식·멱등 응답·compose 처리 4건을 결정함

## 맥락
- 해결하려던 작업: 결제 담당이 함께 구현하게 된 Mock PG 서버(`mock-pg`)의 설계 `docs/design/payment/mock-pg.md`(제안 상태)를 구현 전에 검증한다.
- AI 활용 방식: Claude Code가 쓴 설계를 Opus 리뷰 에이전트가 기존 어댑터·계약 테스트 코드, Tomcat·Spring·JDK 소스와 대조해 리뷰했다. 지적을 사용자에게 설명한 뒤 결정이 필요한 4건을 선택지로 물었다.

## AI 제안
- 리뷰 지적: 차단 1건(환불 멱등 확인이 원자 구간 밖이라 같은 `refundId` 동시 요청에서 오탐 NMC와 환불 누계 이중 가산), 중요 8건(IN_FLIGHT 고착·reset 경합, 호출 수 계수 부재로 V1 검사 무의미, 장애 주입 `count` 전역 소비, 응답 유실 구현 방식, 같은 JVM 기동의 장애, compose 프로필, 테스트 개수 오류, 멱등 헤더 결정 누락), 사소 16건.
- 결정 선택지와 AI 추천: 개정 범위(전부 반영 추천), 계약 테스트 실행(`bootJar` 별도 프로세스 추천), 처리 중 승인 재요청(202 유지 / 409, 추천 없음), compose 프로필(문서에 기록하고 팀 확인 대기 추천).

## 판단
- 결과: 수용. 4건 모두 선택했고 3건은 AI 추천 그대로, 1건(처리 중 승인 재요청)은 202 유지 대신 409 `REQUEST_IN_PROGRESS`를 골랐다.
  1. 리뷰 지적을 전부 `mock-pg.md`에 반영한다.
  2. 계약 테스트는 `mock-pg:bootJar`를 별도 프로세스로 띄워 돌린다(같은 JVM, Testcontainers 제외).
  3. 처리 중 승인 재요청은 409 `REQUEST_IN_PROGRESS`로 한다(조회·취소의 `IN_FLIGHT`는 202 유지).
  4. compose의 `local` 프로필 문제는 설계가 정하지 않고 문서에 기록해 팀 확인을 기다린다(S-30).
- 판단자: junhyun
- 근거: 미기재(선택지만 고름). 사용자는 이에 앞서 글자만으로는 흐름이 안 보인다며 용어집·전체 그림·시퀀스 해설서를 요청했고, 그 자료를 본 뒤 결정했다.
- 검토한 대안: 개정 범위는 "차단과 중요만", "B1만"이 있었다. 실행 방식은 (A) 같은 JVM, (B) Testcontainers가 있었다. compose는 override 파일 직접 추가, `local`에서도 주입 허용이 있었다.

## 반영
- `docs/design/payment/mock-pg.md` 전체 개정(원장 `Ledger` 교체 구조, 호출 종류별 계수, 호출 단위 장애 주입과 모드×호출 표, 환불 `compute` 원자화, Valve 기반 응답 유실 1안, 결정 기록 7절, 스파이크 단계가 있는 PR 분할).
- `pg-integration.md` 프로토콜 표, `failure-scenarios.md` V1 문구, `sa-change-requests.md` S-28 정정과 S-29·S-30 추가.
- 해설서(Artifact)에 리뷰 지적별 사고 시나리오를 정리했다.

## 검증
구현 전. 리뷰 지적 중 JDK `HttpClient`의 GET 자동 재시도와 Tomcat `CLOSE_NOW`로 0바이트가 되는지는 소스 읽기에 근거한 추론이다. 구현 첫 단계(스파이크)에서 원시 소켓 테스트로 확인한다. 테스트 개수(공통 23개, 어댑터 고유 8개)는 코드의 `@Test`를 세어 직접 확인했다.

## 판단 이력
- 2026-10-30 수용: 리뷰 지적 전부 반영, 실행 방식 (C), 처리 중 재요청 409, compose는 팀 확인 대기.

## 발표·면접 포인트
- 구현 전 설계를 별도 Opus 에이전트가 실제 코드와 소스에 대조해 검증하자, 환불 멱등 확인이 원자 구간 밖에 있어 재시도가 환불을 수동 확인으로 오탐하는 동시성 결함이 나왔다. 코드를 쓰기 전에 설계 단계에서 잡았다.
- 테스트 대역(Mock PG)도 실제 서버처럼 호출 횟수를 세고 원자성을 지켜야 "중복 승인 없음" 같은 검증이 의미를 갖는다는 판단.
