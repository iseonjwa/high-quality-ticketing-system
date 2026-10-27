---
title: Config Server와 같은 저장소의 native 설정 폴더 선택
date: 2026-10-26
updated: 2026-10-26
judge: Junhyun An
ai: Codex
verdict: 수용
area: 기타
presentation: 아님
shared: 예
---

# Config Server와 같은 저장소의 native 설정 폴더 선택

## 맥락

ISE-5의 첫 공통 모듈 초안을 전체 Spring Boot 워크스페이스와 M1 인증 기반으로 확장했다.

## AI 제안

SA가 Config Server를 제외한다는 사실을 알리고 서비스별 설정만 둘지 Config Server를 추가할지 물었다. 추가 결정 뒤 설정 저장소 대안을 제시했다.

## 판단

- 결과: 수용
- 판단자: Junhyun An
- 근거: 미기재
- 사용자 판단: 사용자는 "Config Server도 추가"와 "같은 저장소의 설정 폴더"를 선택하고 구현 계획을 승인했다.

## 반영

[ADR](../../decisions/0003-config-server-native.md), Notion SA MASTER v3, 저장소 SA, ISE-5와 실제 구성에 반영한다. 사용자 결정과 팀 합의를 구분한다.

## 검증

실행 명령과 검증 결과는 [워크스페이스 가이드](../../guides/workspace.md)에 둔다.

## 판단 이력

- 2026-10-26 수용: Config Server와 같은 저장소의 native 설정 폴더 선택

## 발표·면접 포인트

계획의 작업 경계와 완료 기준을 사용자 요구에 맞춰 수정하고, 검증하지 않은 범위를 완료로 표현하지 않는다.
