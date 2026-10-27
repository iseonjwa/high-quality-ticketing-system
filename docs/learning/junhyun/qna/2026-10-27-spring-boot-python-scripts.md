---
title: Spring Boot 프로젝트의 개발·검증 스크립트는 왜 Python으로 작성했는가
date: 2026-10-27
updated: 2026-10-27
asker: Junhyun An
level: 기초
tags: [spring-boot, python, gradle, testing, automation]
---

# Spring Boot 프로젝트의 개발·검증 스크립트는 왜 Python으로 작성했는가

## 질문

> 근데 스프링부트인데 스크립트들은 왜 파이썬이야?

후속 지적:

> 내부 테스트코드로 처리해야할 것 같은뎅 아닌가!

## 짧은 답

처음에는 개발 편의상 Python에 실행 준비와 앱 검증이 함께 들어 있었다. Spring Boot의 요구사항은 아니다.
사용자의 지적대로 API·인증·앱 기동 정책은 저장소의 Java·JUnit 테스트로 관리하도록 정리했다.
전체 스택을 사용하는 검증도 내부 JUnit 코드로 작성할 수 있으며, 일반 모듈 테스트와 실행 태스크를 구분하면 된다.
환경·프로세스 준비 도구는 우선 유지한다. 현재 경계와 명령의 정본은 [테스트 가이드](../../../guides/testing.md)에 있다.

## 자세히

### 현재 언어별 역할

| 구분 | 사용 도구 | 현재 역할 |
|---|---|---|
| 애플리케이션 | Java·Spring Boot | 요청 처리, 인증, DB·Redis·Kafka 연동 |
| 애플리케이션 단위·통합 테스트 | Java·JUnit·Gradle | 서비스 분기와 실제 PostgreSQL·Redis·HTTP 동작 검사 |
| 전체 스택 시스템 검증 | Java·JUnit·Gradle | API·연동·관측성·장애 시나리오 검사 |
| 개발 환경 준비 | Python·Bash | 환경 생성, 프로세스 실행, 포트 설정 검사와 검사 순서 조율 |

앱을 Java로 만든다고 주변 자동화까지 Java로 작성해야 하는 제약은 없다. 다만 언어를 추가할 때 얻는 편의와 팀의 유지 비용을 비교해야 한다.

### 이 저장소의 실제 스크립트

- [init-local-env.py](../../../../script/init-local-env.py): 개인 개발용 값을 생성해 환경 파일을 만든다. 기존 파일을 덮어쓰지 않는다.
- [workspace_env.py](../../../../script/workspace_env.py): Compose의 환경변수 해석 결과를 읽어 JVM 실행과 검증에서 같은 값을 사용한다.
- [run-app.py](../../../../script/run-app.py): 실행 환경을 준비해 `gradlew :서비스:bootRun`을 호출한다. 앱의 실제 실행은 JVM이 맡는다.
- [WorkspaceSystemTest](../../../../system-tests/src/test/java/iseonjwa/verification/WorkspaceSystemTest.java): 기존 Python 스모크의 API·연동·관측·장애 검증을 옮긴 JUnit 테스트다.
- [check-port-exposure.py](../../../../script/check-port-exposure.py): Compose가 출력한 JSON에서 포트 공개 규칙을 검사한다.
- [ConfigServerUnavailableTest](../../../../system-tests/src/test/java/iseonjwa/verification/ConfigServerUnavailableTest.java): 별도 JVM을 실행해 설정 서버가 없을 때 실패하는지 JUnit으로 검사한다.
- [check.sh](../../../../script/check.sh): Python 검사, Compose 준비, Gradle 빌드·테스트 등의 순서를 실행한다.

[Dockerfile](../../../../Dockerfile)의 최종 단계는 JAR를 복사하고 `java -jar`로 앱을 시작한다. 이 스크립트들이 업무 서버 안에서 별도의 Python API를 실행하는 구성은 아니다.
개발 스크립트를 사용하거나 현재 CI 검사를 실행할 때는 Python이 필요하다. [CI](../../../../.github/workflows/build.yml)도 Python 검사 명령을 호출한다.

### 처음 Python을 선택한 이유와 비용

현재 스크립트는 Python 표준 라이브러리와 저장소의 로컬 모듈을 사용한다. 별도 pip 패키지를 설치하지 않고 JSON 처리, HTTP 요청, 파일 작업, 자식 프로세스 실행을 작성할 수 있다.
Bash로도 가능하지만 여러 JSON 필드 검사, 오류·시간 제한 처리, 테스트 데이터 생성이 늘어나면 Python이 작성·검토하기 편하다는 선택이다.
이는 구현 편의에 대한 판단이며 모든 Spring Boot 프로젝트에서 Python이 더 낫다는 근거는 아니다.

팀에는 Python 실행 환경과 별도의 코드·테스트를 이해하고 유지하는 비용이 생긴다. Java와 Python 양쪽에서 API·설정 계약을 검사하므로 계약이 바뀌면 해당 검증도 함께 맞춰야 한다.
OS·프로세스 자동화에 적합한 선택이어도, 팀이 Java만 다루고자 한다면 언어 통일의 이점이 더 클 수 있다.

### 현재 Java로 정리한 범위

업무 규칙과 서비스 단위·통합 테스트는 각 모듈에 유지하고, 전체 HTTP·연동 시나리오는 검증 전용 `system-tests`에 두었다.
설정 서버 기동 실패는 기본 Gradle 테스트에 포함한다. 실행 중인 전체 스택이 필요한 검증은 별도 `systemTest` 태스크로 실행한다.
Gradle이 Python 검증을 감싸는 형태가 아니라, 판정 로직을 Java 테스트로 실제 이관했다. 이전 Python 앱 검증 2개는 제거했다.

## 이 프로젝트에서는

기존 애플리케이션 검사와 별도로 있던 Python 스모크를 시스템 JUnit 13개로, 설정 서버 장애 검사를 기본 JUnit 1개로 이관해 실행했다.
Python 14개는 환경 해석·JVM 실행 환경·포트 설정 도구 자체의 테스트다. 환경 도구 전체 제거까지 확정한 발언으로 확대하지 않는다.
[ADR 0011](../../../decisions/0011-junit-verification-boundary.md)과 [판단 기록](../../../ai-judgments/junhyun/2026-10-27-junit-test-boundary.md)에 지적과 구현 범위를 남겼다.
기본 검사에서 JUnit 106개와 개발 도구 Python 14개가 통과했고, 실제 스택의 시스템 JUnit 13개도 통과했다. JVM 모드에서도 11개를 확인했으며 재기동·pause 옵션 2개는 생략했다. 검사 후 Compose 앱·관측 설정을 복구했다.

## 면접 포인트

- 서비스 구현 언어와 자동화 언어를 구분하는 이유: 애플리케이션 내부의 기능·테스트와 여러 실행 프로세스를 외부에서 조율하는 도구의 목적이 다르기 때문이다.
- 자동화에 다른 언어를 추가할 때 보는 기준: 구현 편의뿐 아니라 팀 숙련도, 실행 환경, 계약 중복과 유지 비용을 함께 비교한다.

## 참고

- [Python subprocess](https://docs.python.org/3/library/subprocess.html): 외부 프로세스 실행, 종료 코드, 환경변수와 시간 제한 처리.
- [Python urllib.request](https://docs.python.org/3/library/urllib.request.html): 표준 라이브러리의 HTTP 요청 기능.
- [Gradle custom tasks](https://docs.gradle.org/current/userguide/custom_tasks.html): Gradle에 별도 작업을 정의하는 방법. 일반적인 기능 설명을 참고했으며 저장소의 Gradle Wrapper 버전을 변경하지 않았다.
