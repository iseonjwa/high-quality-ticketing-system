---
title: Python 앱 검증 대신 내부 JUnit 테스트로 정리
date: 2026-10-27
updated: 2026-10-27
judge: Junhyun An
ai: Codex
verdict: 거부
area: 협업
presentation: 아님
shared: 예
---

# Python 앱 검증 대신 내부 JUnit 테스트로 정리

## 맥락

- 해결하려던 작업: Spring Boot 프로젝트의 Python 스크립트가 맡는 역할과 테스트 코드의 위치를 검토한다.
- AI 활용 방식: Python을 쓴 이유를 묻고 내부 테스트 코드로 관리하는 편이 맞지 않느냐고 지적했다.

## AI 제안

Codex는 Python 표준 라이브러리로 HTTP·프로세스 자동화를 작성할 수 있다는 편의를 설명하고 Java 중심 팀이라면 JUnit·Gradle로 정리할 수 있다고 답했다.
후속 지적에 따라 앱 동작 판정은 JUnit으로 이관하고 환경·프로세스 준비는 도구에 남기는 방향으로 정리했다. 구체 범위와 검증은 [ADR 0011](../../decisions/0011-junit-verification-boundary.md)에 있다.

## 판단

- 결과: 거부. 앱 동작 검증을 Python 스모크에 별도로 두는 선택에 대한 지적이다.
- 판단자: Junhyun An
- 사용자 원문: "내부 테스트코드로 처리해야할 것 같은뎅 아닌가!"
- 근거: 내부 테스트 코드로 처리해야 한다는 선호를 밝혔다.
- 범위: Python 도구 전체 제거까지 확정한 발언으로 확대하지 않는다. API·인증·기동 검증의 JUnit 이관을 우선 진행한다.

## 반영

- 검증 전용 `system-tests`에 API·인증·연동·관측·장애 시나리오와 설정 서버 장애 테스트를 둔다.
- 기본 Gradle 빌드와 CI에 설정 서버 장애 검증을 포함하고, 실행 스택 검증은 별도 Gradle 태스크로 제공한다.
- Python 앱 검증 2개를 제거하고 JVM 실행 도구는 JUnit을 호출한다.
- [테스트 가이드](../../guides/testing.md)에 검증 경계와 이관 대응을 남긴다.

## 검증

`bash script/check.sh`가 통과했다. 기본 JUnit 106개(설정 서버 장애 1개 포함), 개발 도구 Python 14개, 포트 공개·전체 빌드·기록·diff 검사를 확인했다.
`./gradlew :system-tests:systemTest -PcoldStart=true -PredisFailure=true --no-daemon`에서 실제 스택 시스템 테스트 13개가 모두 통과했다.
JVM 실행 모드도 확인했다. 앱 7개의 업무·관리 소켓 loopback과 Eureka 등록 주소를 확인하고 시스템 테스트 11개가 통과했다. Docker 재기동·Redis pause 옵션 2개는 JVM 모드에서 의도적으로 생략했다.
검사 JVM은 종료하고 기존 Compose 앱·관측 설정을 정상 상태로 복구했다. 원격 CI 실행은 이번 검증 범위 밖이다.

## 판단 이력

- 2026-10-27 거부: 앱 동작 검증을 내부 테스트 코드로 정리해야 한다고 지적했다.
- 2026-10-27 범위 확정: 이후 "Python 도구까지 모두 Java·Gradle로 통일"을 선택했다. 앞의 검증은 첫 이관 시점의 기록이며 현행 범위·검증은 [확대 기록](2026-10-27-java-gradle-tools.md)에 있다.

## 발표·면접 포인트

- 동작 판정은 테스트 프레임워크에 두고 실행 준비는 도구에 두어 테스트 발견·실행·리포트를 한곳에서 관리한다.
- 실제 HTTP·DB·Redis를 사용하는 통합 테스트도 내부 JUnit 코드로 작성할 수 있다.
