---
title: IntelliJ에서 "Gradle 프로젝트로 불러온다"는 것은 무엇이고 왜 필요한가
date: 2026-10-27
updated: 2026-10-27
asker: GGangMY
level: 기초
tags: [gradle, intellij, build-tool, ide]
---

# IntelliJ에서 "Gradle 프로젝트로 불러온다"는 것은 무엇이고 왜 필요한가

## 질문
Gradle 프로젝트? (IntelliJ에서 하이라이팅이 사라지고 어노테이션이 흰색으로 보인 원인이 "Gradle 프로젝트로 불러오지 않아서"라고 들은 뒤)

## 짧은 답
- 코드를 어떻게 빌드할지(모듈 구성, 의존 라이브러리, Java 버전)는 **Gradle 파일**(`settings.gradle.kts`, `build.gradle.kts`)에 적혀 있다.
- IntelliJ는 그 파일을 직접 이해하지 않는다. IntelliJ 안에는 자기만의 프로젝트 모델(모듈, 소스 폴더, 라이브러리, SDK)이 따로 있다.
- "Gradle 프로젝트로 불러온다(Load / Link / Sync)"는 **IntelliJ가 Gradle을 한 번 실행해 그 결과로 자기 모델을 채우는 것**이다.
- 불러오지 않으면 IntelliJ는 `src/main/java`가 소스인지, Spring jar가 어디 있는지 모른다. 그래서 `@SpringBootApplication`을 해석하지 못해 흰색으로 보인다.

## 자세히

### 두 가지 "프로젝트"

| | Gradle이 아는 프로젝트 | IntelliJ가 아는 프로젝트 |
|---|---|---|
| 정의 위치 | `settings.gradle.kts`, 모듈마다 `build.gradle.kts` | `.idea/` 폴더, `*.iml` 파일 |
| 담는 것 | 모듈 목록, 의존성, Java 툴체인, 빌드 작업 | 모듈, 소스·테스트 폴더 표시, 라이브러리 경로, SDK |
| 누가 쓰나 | 터미널 `./gradlew build`, Docker 빌드, CI | 에디터 하이라이팅, 자동 완성, 이동, 리팩터링 |
| 저장소에 올리나 | 올린다(팀 공용) | 올리지 않는다(`.idea/`는 `.gitignore`) |

같은 코드를 두 도구가 따로 이해한다. 둘이 어긋나지 않게, IntelliJ 모델은 **Gradle 파일에서 만들어지게** 하는 것이 표준이다.

### 불러오기(Sync)가 하는 일
1. IntelliJ가 Gradle을 실행해 `settings.gradle.kts`를 읽는다 → 모듈 11개를 안다.
2. 모듈마다 `build.gradle.kts`와 컨벤션 플러그인을 평가한다 → 의존성을 내려받고 jar 위치를 받아 온다.
3. 그 결과로 IntelliJ 모듈을 만들고 `src/main/java`, `src/test/java`, `src/main/resources`를 표시한다. 라이브러리를 연결하고, 툴체인 기준으로 Java 21을 잡는다.
4. 이후 Gradle 파일이 바뀌면 오른쪽 위의 코끼리 아이콘(Load Gradle Changes)으로 다시 동기화한다.

### 일반 폴더로 열었을 때와 비교

| 상태 | 하이라이팅·자동 완성 | 실행 | 문제 |
|---|---|---|---|
| 일반 IntelliJ 모듈(.iml만) | 문법 색만. 라이브러리 클래스를 몰라 어노테이션 흰색 | 수동 설정 필요 | 의존성을 손으로 넣어야 하고 Gradle과 어긋난다 |
| Gradle 연결 | 전부 동작 | 바로 실행 | Gradle 파일이 바뀌면 다시 동기화해야 한다 |

### 이 저장소가 일반 모듈로 열린 이유
처음 clone했을 때 저장소에는 문서만 있었다. IntelliJ는 빌드 파일이 없으니 일반 폴더 프로젝트(`high-quality-ticketing-system.iml`)로 만들었다. 나중에 Gradle 파일이 커밋으로 들어와도 IntelliJ는 이미 만든 모델을 자동으로 바꾸지 않는다. 확인 방법: `.idea/gradle.xml`이 없으면 Gradle과 연결되지 않은 것이다.

### Maven과의 관계
Maven도 같은 구조다(`pom.xml` → IntelliJ가 불러옴). Gradle은 빌드 스크립트가 코드(Kotlin DSL)라 유연하고, 증분 빌드·빌드 캐시가 있어 멀티모듈에서 빠르다. 대신 스크립트를 평가해야 해서 첫 동기화가 느리다.

## 이 프로젝트에서는
- 연결 방법: `settings.gradle.kts`를 열었을 때 뜨는 **Load Gradle Project**를 누르거나, 우클릭 → **Link Gradle Project**.
- Gradle JVM은 `Settings > Build Tools > Gradle`에서 21로 둔다. 컴파일 JDK는 툴체인(`java-conventions`)이 21로 맞추지만, Gradle을 실행할 JVM은 IntelliJ 설정을 따른다.
- 루트에 `build.gradle.kts`가 없고 `settings.gradle.kts`만 있는 것은 정상이다. 공통 설정은 `build-logic/` 컨벤션 플러그인에 있다.

## 면접 포인트
- "빌드 도구와 IDE의 관계는?": 빌드 정의의 원본은 Gradle 파일이고, IDE는 그것을 불러와 자기 모델을 만든다. 그래서 IDE 설정을 저장소에 올리지 않아도 팀원 모두 같은 구성을 얻고, 터미널·CI·Docker 빌드와 IDE가 같은 결과를 낸다.

## 참고
- IntelliJ IDEA 문서 "Gradle" (Link/Load Gradle project)
- 이 저장소 `.idea/` 상태 확인(2026-10-27): `gradle.xml` 없음, `misc.xml`의 SDK `openjdk-27`
