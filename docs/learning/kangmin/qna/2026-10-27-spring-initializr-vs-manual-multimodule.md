---
title: Spring Initializr로 만들었으면 Gradle 연결과 초기 구성이 알아서 됐을까
date: 2026-10-27
updated: 2026-10-27
asker: GGangMY
level: 기초
tags: [spring-initializr, gradle, multi-module, intellij]
---

# Spring Initializr로 만들었으면 Gradle 연결과 초기 구성이 알아서 됐을까

## 질문
태초에 Spring Initializr로 만들었으면 (IntelliJ Gradle 연결, 빌드 구성 같은 것이) 알아서 딸려왔을 부분인가?

## 짧은 답
- **IntelliJ 하이라이팅 문제는 피했을 것이다.** 다만 Initializr 덕분이라기보다, 빌드 파일이 있는 상태로 처음 열었기 때문이다. IntelliJ는 프로젝트를 처음 열 때 Gradle 파일을 보면 알아서 불러온다. 이 저장소는 문서만 있을 때 열어서 일반 폴더가 됐다.
- **빌드 뼈대 일부는 딸려온다**: wrapper, `build.gradle(.kts)`, main 클래스, 테스트 한 개, 설정 파일, `.gitignore`.
- **딸려오지 않는 것이 더 많다**: Initializr는 **서비스 하나짜리(단일 모듈)** 프로젝트를 만든다. 서비스 7개를 묶는 멀티모듈, 컨벤션 플러그인, 버전 카탈로그, 전체 인프라 compose, Config 서버의 `config-repo`, 공용 Dockerfile은 직접 만들어야 한다.

## 자세히

### Initializr가 만들어 주는 것

| 항목 | Initializr | 이 저장소 |
|---|---|---|
| Gradle wrapper (`gradlew`) | O | O (같은 것을 직접 생성) |
| `settings.gradle(.kts)` | O (모듈 1개) | O (모듈 11개) |
| `build.gradle(.kts)` | O. 버전을 파일 안에 적고 `io.spring.dependency-management` 플러그인 사용 | 모듈별로는 플러그인 한 줄. 버전은 `libs.versions.toml`, BOM은 Gradle `platform()` |
| Java 툴체인 | O (`toolchain { languageVersion = 21 }`) | O (컨벤션 플러그인) |
| main 클래스 + `contextLoads` 테스트 | O | O (서비스마다 직접 작성) |
| 설정 파일 | `application.properties` (기본) | `application.yml` + `config-repo/` |
| 의존성 선택 | Config Server, Eureka, Gateway, JPA, Flyway, PostgreSQL, Testcontainers 등 체크로 추가 | 같은 의존성을 카탈로그로 관리 |
| Testcontainers | 선택하면 `TestcontainersConfiguration` 클래스까지 생성 | 서비스 테스트 안에 직접 작성 |
| Docker Compose | "Docker Compose Support"를 고르면 선택한 DB만 담은 `compose.yaml` + 실행할 때 자동 기동 | 인프라 9개 + 서비스 7개, 메모리 상한, 포트 노출 규칙, healthcheck 순서 |
| 멀티모듈 | X | O |
| Dockerfile, `config-repo`, Prometheus·Grafana 설정 | X | O |

### 실무에서 흔한 방식
1. 서비스마다 Initializr로 프로젝트를 만든다.
2. 하나의 저장소로 옮기면서 서비스마다 생긴 wrapper와 settings를 지우고, 루트 `settings.gradle.kts`에 `include`한다.
3. 반복되는 빌드 설정(Java 버전, BOM, 테스트 설정)을 공통으로 뺀다(`subprojects {}` 또는 컨벤션 플러그인).

이 저장소는 3번의 결과를 처음부터 직접 만든 셈이다.

### 비교: Initializr로 시작 vs 처음부터 직접 구성

| | Initializr로 만든 뒤 합치기 | 처음부터 직접 (선택) |
|---|---|---|
| 장점 | 빠르다. 표준 구조라 낯설지 않다. 의존성 이름을 고를 필요가 없다 | 처음부터 모듈 11개를 한 구조로 묶는다. 중복 wrapper·설정이 없다. 버전이 한 곳에 있다 |
| 단점 | 서비스마다 같은 설정이 7번 생긴다. 합치는 정리 작업이 따로 든다. 서비스마다 Boot 버전이 어긋나기 쉽다 | 의존성 이름과 설정 방법을 직접 확인해야 한다. Boot 4에서 바뀐 스타터 이름은 BOM을 확인해서 골랐다 |

### 앞으로 쓸 수 있는 방법
- 새 서비스나 기능을 추가할 때, start.spring.io에서 같은 버전(Boot 4.1.2, Java 21)으로 의존성을 골라 **Explore**만 눌러 본다. 생성된 `build.gradle.kts`를 참고해 카탈로그에 이름을 옮기면 의존성 이름을 잘못 쓰는 실수를 줄일 수 있다.
- IntelliJ의 New Project → Spring Boot도 내부적으로 Initializr를 쓴다.

## 이 프로젝트에서는
- 하이라이팅 문제의 직접 원인은 clone한 시점이다: `.idea/gradle.xml` 없음. 해결은 `settings.gradle.kts`에서 Load/Link Gradle Project.
- Initializr로 시작했더라도 팀원이 문서만 있던 시점에 clone해 두었다면 같은 문제가 생긴다.

## 면접 포인트
- "Initializr를 안 쓴 이유?": 단일 서비스는 Initializr가 빠르지만, MSA 모노레포는 서비스 여러 개를 하나의 멀티모듈로 묶고 공통 빌드 설정을 한 곳에 두는 것이 목적이었다. Initializr로 만들어도 결국 합치는 작업이 필요하다.

## 참고
- start.spring.io (Spring Initializr)
- 같은 날 노트: `2026-10-27-intellij-gradle-project.md`, `2026-10-27-initial-environment-setup.md`
