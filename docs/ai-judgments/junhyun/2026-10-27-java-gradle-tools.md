---
title: 이관 범위를 Python 도구 전체의 Java·Gradle 통일로 확대
date: 2026-10-27
updated: 2026-10-27
judge: Junhyun An
ai: Codex
verdict: 부분 수용
area: 협업
presentation: 아님
shared: 예
---

# 이관 범위를 Python 도구 전체의 Java·Gradle 통일로 확대

## 맥락

- 해결하려던 작업: 앱 검증 이관 뒤 남길 개발 도구의 언어와 실행 방식을 정한다.
- AI 활용 방식: Codex가 앱 검증만 JUnit으로 옮기고 실행·환경 스크립트는 유지하는 안을 우선 추천했다. 전체 도구를 Java·Gradle로 옮기는 대안도 제시했다.

## AI 제안

앱 검증은 JUnit으로 옮기고 환경 준비·실행·포트 도구는 유지하는 안을 추천했다. 사용자 선택 뒤 실제 구현을 Java로 이관하고 Gradle 진입점과 JUnit 회귀로 연결했다.
구체 범위는 [ADR 0012](../../decisions/0012-java-gradle-workspace-tools.md)에 있다.

## 판단

- 결과: 부분 수용. JUnit 이관 방향을 수용하되 Python 도구 유지 추천은 채택하지 않고 범위를 전체 도구로 확장했다.
- 판단자: Junhyun An
- 사용자 원문: "Python 도구까지 모두 Java·Gradle로 통일"
- 후속 요청: "다 하고 푸쉬까지 해줘!"
- 근거: Java·Gradle로 통일하는 범위를 명시했다. 추가 이유는 미기재다.
- 범위: 기존 앱·공통 코드와 SA 설계를 유지하고 개발 도구·검증·문서·CI를 이관한다.

## 반영

- `workspace-tools`에 dotenv·환경 생성·앱 환경·프로세스·포트 구현과 JUnit 회귀를 둔다.
- Gradle `initLocalEnv`, `runLocal`, `checkPortExposure`, `verifyWorkspace`, `verifyJvm` 진입점을 제공한다.
- `system-tests`도 같은 Java 환경 해석·프로세스 도구를 사용한다. JVM 모드는 JUnit이 앱을 소유하고 종료한다.
- 기존 Python 파일과 테스트를 제거하고 CI·워크스페이스·테스트 문서를 갱신한다.

## 검증

기본 JUnit 124개, 실제 Compose 스택의 시스템 13개가 통과했다. JVM 모드 11개와 소유 JVM 정리, `runLocal` 기동·중단도 확인했다.
기존 `.env`의 유지와 신규 파일 0600·서명 키 분리, dotenv 문법·우선순위, 포트 규칙, 시간 초과·프로세스 종료를 검증했다.
운영·부하·원격 CI 실행은 별도 범위다. 푸시 결과는 최종 전달 시 보고한다.

## 판단 이력

- 2026-10-27 부분 수용: 앱 검증 이관 방향을 유지하면서 Python 도구 유지안 대신 전체 통일을 선택했다.

## 발표·면접 포인트

- 자동화 구현 편의와 팀 공통 언어의 유지 비용을 비교하고 사용자가 범위를 정했다.
- 언어만 감싸는 래퍼 대신 동작과 회귀 검증을 이관해 Python 실행 의존성을 없앴다.
