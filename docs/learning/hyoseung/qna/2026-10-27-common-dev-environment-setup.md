---
title: 프로젝트 시작 전 공통 개발 환경은 어떻게 세팅하는가
date: 2026-10-27
updated: 2026-10-27
asker: hyos1
level: 기초
tags: [spring-boot, spring-cloud, gradle, multimodule, config-server, docker-compose]
---

# 프로젝트 시작 전 공통 개발 환경은 어떻게 세팅하는가

## 질문
프로젝트 시작 전 공통 환경을 세팅하려고 하는데 어떻게 하면 되는가. 팀은 각자 세팅해 보고 좋은 것으로 합치기로 했다.

## 짧은 답
SA MASTER v3.1 기준 공통 환경은 Gradle 멀티모듈 뼈대, 공통 모듈(`common`·`common-response`·`common-web`), Config 서버와 `config-repo/`, Eureka·Gateway, 인프라용 docker-compose와 `.env.example`이다. 버전은 Java 21 · Spring Boot 4.1.x · Spring Cloud 2025.1.x · Gradle 9.x다(#30 잠정). 각자 만든 뒤 합치려면 패키지 이름, 포트, 모듈 이름, 빌드 스크립트 언어를 시작 전에 맞춰야 비교가 쉽다.

## 자세히
### 공통 환경이란 무엇인가 (추가 질문: "공통 환경 세팅이 뭘 하는 건지 설명해 줘")
도메인 기능(공연, 대기열, 결제)과 상관없이 모든 서비스가 똑같이 필요로 하는 바닥이다. 다섯 명이 서비스를 따로 만들어도 같은 방식으로 빌드되고, 같은 형태로 응답하고, 같은 문으로 요청을 받게 하는 것이 목적이다. 이 바닥이 없으면 각자 다른 버전, 다른 응답 형태, 다른 실행 방법으로 만들게 되고 나중에 연결할 때 맞추는 비용이 커진다.

| 구성 요소 | 하는 일 | 비유 |
|---|---|---|
| Gradle 멀티모듈 뼈대 | 한 저장소 안에 여러 서비스·모듈을 두고, Java·Spring 버전과 라이브러리 버전을 루트 한 곳에서 정한다 | 단지 공용 설계 기준 |
| `common` | 이벤트 봉투(Kafka 메시지 공통 필드), `BaseEntity`(생성·수정 시각 같은 공통 컬럼) | 공용 부품 |
| `common-response` | 성공 `{status, message, data, traceId}`, 실패 `{status, code, message, errors, traceId}` 응답 클래스와 `ErrorCode` 인터페이스 | 공용 서식 |
| `common-web` | 예외가 나면 실패 응답 형태로 바꿔 주는 전역 예외 처리기(MVC 서비스용) | 서식 자동 작성기 |
| Config 서버 + `config-repo/` | 각 서비스 설정(yml)을 한 폴더에 모아 두고 서비스가 기동할 때 받아 간다. 프로필(local·test·loadtest·prod)별 값도 여기 있다 | 중앙 설정실 |
| Eureka | 서비스가 뜨면 "나 user-service, 주소는 여기"라고 등록한다. 다른 쪽은 이름으로 주소를 찾는다 | 전화번호부 |
| Gateway | 외부 요청이 들어오는 유일한 문(8080). JWT를 검증해 `X-User-Id`·`X-User-Role` 헤더를 붙이고, 경로를 보고 알맞은 서비스로 보낸다 | 정문 경비실 |
| docker-compose | DB, Redis, Kafka, 모니터링 도구를 명령 한 번으로 띄운다 | 공용 설비 스위치 |
| `.env` / `.env.example` | 비밀번호·서명 키 같은 비밀값은 `.env`(커밋 안 함), 어떤 값이 필요한지 목록은 `.env.example`(커밋함) | 금고와 금고 목록 |

compose로 띄우는 인프라의 역할:
- PostgreSQL 3개: user·reservation·payment가 각자 DB를 가진다(서비스마다 데이터 소유).
- Redis: 대기열 순번, 캐시, 로그아웃 토큰 블랙리스트.
- Kafka: 예매와 결제가 이벤트(`payment.result` 등)로 주고받는 통로.
- Prometheus·Grafana: 처리량·응답 시간 같은 지표를 모으고(Prometheus) 그래프로 보여 준다(Grafana).
- Zipkin: 요청 하나가 Gateway → 서비스 → DB를 지나며 어디서 오래 걸렸는지 추적한다.

요청 하나가 지나가는 길:
```
클라이언트 → Gateway(JWT 검증, 헤더 추가)
           → Eureka에서 user-service 주소 확인
           → user-service(설정은 기동 때 Config 서버에서 받음)
           → postgres-user
           → 응답은 common-response 형태로 반환
```

공통 환경이 끝났다는 기준(M1 걷는 뼈대): compose 한 번으로 인프라와 Gateway·Eureka·User가 뜨고, 회원가입 → 로그인 → Gateway를 거친 요청에서 `X-User-Id`·`X-User-Role`이 서비스까지 전달되며, Prometheus·Zipkin에서 지표와 트레이스가 보인다(`docs/core/milestones.md`).

### 초기 환경과 공통 모듈의 차이 (추가 질문: "각각 뭘 해야 하는지 읽어도 이해가 안 된다")
- 초기 환경은 앱이 돌아갈 무대다. 앱을 빌드하는 틀(Gradle 멀티모듈), 설정을 나눠 주는 곳(Config 서버), 서비스 주소록(Eureka), 정문(Gateway), DB·Redis·Kafka·모니터링을 한 번에 켜는 스위치(docker compose와 `.env`)다. 다 되면 "명령 하나로 전부 켜지고, 요청이 정문을 지나 서비스까지 간다".
- 공통 모듈은 모든 서비스가 가져다 쓰는 공용 코드다. 응답 모양(`common-response`), 에러를 그 모양으로 바꿔 주는 처리기(`common-web`), DB 공통 컬럼과 Kafka 메시지 봉투(`common`)다. 다 되면 "새 서비스가 의존성 한 줄만 추가해도 응답과 에러가 팀 규칙대로 나간다".
- 구현 결과와 실행 방법은 `docs/guides/local-environment.md`에 있다.

### M1이 "세팅"만인가 (추가 질문: "세팅 마일스톤은 딱 이것까지만 하는 건가")
아니다. M1(10/27–10/29)은 세팅보다 넓어서 인증까지 들어간다(`docs/core/milestones.md`).

| 구분 | M1에 들어가는 것 | 누가 |
|---|---|---|
| 공통 모듈 | 4계층 패키지 구조, `common-response`·`common-web`, 전역 예외 처리, 이벤트 공통 필드 | 전원이 각자 만들어 비교 후 병합 |
| 인증 | 회원가입·로그인·토큰 재발급·로그아웃(Redis 블랙리스트), 내 정보 조회, 운영자 계정 시드 | 이슈를 가져간 사람 |
| Gateway | 라우팅, JWT 검증 후 `X-User-Id`·`X-User-Role` 전달, Eureka | 이슈를 가져간 사람 |
| Config 서버 | `config-server`(8888, native)와 `config-repo/` | 이슈를 가져간 사람 |
| 실행 환경 | docker-compose, `.env.example` | 이슈를 가져간 사람 |

- 진행 순서: 계약 합의(10/27) → 공통 모듈 각자 구현 → 비교해 한 브랜치로 병합 → 그 위에 인증을 올려 main 반영. 이후 모든 서비스 브랜치는 여기서 딴다.
- "각자 해 보고 합친다"는 공통 모듈에 해당한다. 인증·Gateway·Config·compose는 이슈 단위로 한 사람이 맡는다.
- Notion M1 페이지(2026-10-27 export 확인)에는 Config 서버 행과 완료 기준의 "서비스가 Config 서버의 설정을 읽는다"가 없다. 저장소 `docs/core/milestones.md`와 SA v3.1(6-2 Must, 17-1 안건 2)에는 M1에 들어 있다. Notion 페이지가 Config 서버 결정(#30, 10/26) 전 내용으로 남아 있는 것으로 보이며, 어느 쪽이 맞는지는 팀 확인이 필요하다.
- M1에 없는 것: 공연·대기열·선점·결제 같은 도메인 기능(M2), `common-messaging`(Outbox·DLQ, M2 결제 담당), Mock PG, 배포(M3), loadtest 측정(M2 이후).

### 전원이 같은 범위를 각자 만들 때의 순서 (추가 질문: "user-service에 web·eureka-client·config client만 넣었는데 다음에 뭘 해야 하나")
팀은 Config 서버를 M1에 넣고, 전원이 M1 전체를 각자 만든 뒤 좋은 것으로 합치기로 했다(2026-10-27). 한 단계마다 눈으로 확인할 수 있는 순서로 쌓는다.

| 단계 | 할 일 | 확인 방법 |
|---|---|---|
| 0 | 멀티모듈 전환: wrapper·`settings.gradle`을 루트로, 버전·BOM은 루트 `build.gradle`로 | `./gradlew projects`에 모듈이 보임 |
| 1 | `config-server` 모듈 + `config-repo/` | `curl localhost:8888/user-service/default`가 설정을 돌려줌 |
| 2 | `eureka` 모듈 | `localhost:8761` 대시보드가 열림 |
| 3 | user-service를 Config·Eureka에 연결 | 기동 로그에 Config 서버 접속, 대시보드에 USER-SERVICE 등록 |
| 4 | `gateway` 모듈, `/api/v1/users/**` → `lb://user-service` | Gateway(8080)로 user-service 테스트 API 호출 성공 |
| 5 | docker-compose(PostgreSQL·Redis·Kafka·모니터링) + `.env.example`, user-service에 JPA·PostgreSQL | user-service가 DB에 붙어 테이블 생성 |
| 6 | 공통 모듈(`common`·`common-response`·`common-web`) | 성공·실패 응답이 11-2 형태로 나옴 |
| 7 | 인증(회원가입·로그인·재발급·로그아웃) + Gateway JWT 필터 | Gateway를 거친 요청에 `X-User-Id`·`X-User-Role`이 붙음 |
| 8 | Actuator·Prometheus·Zipkin 연결 | Prometheus·Zipkin 화면에 지표·트레이스 |

- `spring-cloud-starter-config`를 넣고 `spring.config.import`를 적지 않으면 기동 시 "No spring.config.import property has been defined" 오류로 실패한다. 기본 테스트(`contextLoads`)도 같은 이유로 실패하므로 test 프로필에서 `spring.cloud.config.enabled: false`와 `spring.config.import` 생략(또는 `optional:`)을 둔다. 이 동작은 Spring Cloud Config 클라이언트 문서 기준이며 이 저장소에서 실행 확인은 하지 않았다.
- Gateway 의존성은 `spring-cloud-starter-gateway-server-webflux`, 설정 경로는 `spring.cloud.gateway.server.webflux.*`다(Spring Cloud 2025.x 기준). Boot 3 시절 예제의 `spring.cloud.gateway.routes`를 그대로 쓰지 않는다.

### 버전
- SA 9-1(#30 잠정): Java 21 LTS(Temurin), Spring Boot 4.1.x, Spring Cloud 2025.1.x, Gradle 9.x(툴체인 21). 10/26에 Java 25에서 21로 바뀌었고 팀 전체 합의 전이다.
- Spring Initializr 메타데이터(`https://start.spring.io/actuator/info`, 2026-10-26 확인)에서 Spring Cloud `2025.1.4`의 지원 범위는 Spring Boot `>=4.0.0 and <4.2.0-M1`이다. Boot 4.1.x와 함께 쓸 수 있다.
- Java 21에서는 가상 스레드가 `synchronized` 안에서 캐리어 스레드에 고정되므로 기본으로 끈다(SA 9-1).

### 멀티모듈 뼈대
루트 `settings.gradle`이 모듈 목록을, 루트 `build.gradle`이 Java 버전·저장소·BOM을 모든 모듈에 적용한다. 각 모듈은 버전 없이 의존성 이름만 적는다.

```groovy
// settings.gradle
rootProject.name = 'iseonjwa'
include 'common', 'common-response', 'common-web',
        'config-server', 'eureka', 'gateway', 'user-service'
```

```groovy
// 루트 build.gradle
plugins {
    id 'org.springframework.boot' version '4.1.2' apply false
    id 'io.spring.dependency-management' version '1.1.7' apply false
}

ext { springCloudVersion = '2025.1.4' }

subprojects {
    apply plugin: 'java'
    apply plugin: 'io.spring.dependency-management'

    group = 'com.iseonjwa'
    java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
    repositories { mavenCentral() }

    dependencyManagement {
        imports {
            mavenBom org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES
            mavenBom "org.springframework.cloud:spring-cloud-dependencies:${springCloudVersion}"
        }
    }
    tasks.named('test') { useJUnitPlatform() }
}
```

- 라이브러리 모듈(`common*`)은 `java-library` 플러그인만 쓴다. Boot 플러그인이 없으면 실행 jar(bootJar)가 생기지 않는다.
- 실행 모듈(`config-server`, `eureka`, `gateway`, `user-service`)에는 `org.springframework.boot` 플러그인을 붙인다.
- Gateway는 WebFlux 기반이라 MVC 예외 처리기(`common-web`)를 넣지 않는다(SA 11-2).

### Config 서버 (SA 9-6)
- 백엔드는 native(파일)다. 모노레포의 `config-repo/`를 읽는다. 별도 Git 저장소가 필요 없고 설정 변경도 PR로 리뷰된다.
- 파일 구성: `application.yml`(공통), `{서비스}.yml`, `{서비스}-{프로필}.yml`.
- 서버 설정 예시:

```yaml
# config-server/src/main/resources/application.yml
server:
  port: 8888
spring:
  application:
    name: config-server
  profiles:
    active: native
  cloud:
    config:
      server:
        native:
          search-locations: file:./config-repo   # 컨테이너에서는 file:/config-repo
```

- 클라이언트 설정 예시. 로컬에서는 IntelliJ로 띄운 앱이 `localhost:8888`로 붙는다. test 프로필은 Config 서버 없이 돈다.

```yaml
# user-service/src/main/resources/application.yml
spring:
  application:
    name: user-service      # config-repo/user-service.yml을 찾는 이름
  config:
    import: configserver:${CONFIG_SERVER_URL:http://localhost:8888}
  cloud:
    config:
      fail-fast: true
---
spring:
  config:
    activate:
      on-profile: test
  cloud:
    config:
      enabled: false
```

- 비밀값은 `config-repo`에 넣지 않고 `${DB_PASSWORD}` 같은 자리표시자만 둔다. 실제 값은 `.env`로 주입하고 저장소에는 `.env.example`만 둔다.
- 기동 순서는 config-server → eureka → 서비스다.

### docker-compose (SA 6-2, 9-1, 9-2)
- 로컬 개발에서 compose로 띄우는 것: config-server, PostgreSQL 3개(user·reservation·payment), Redis, Kafka(KRaft, 브로커 1), Kafka-UI, Prometheus, Grafana, Zipkin.
- 앱(gateway, eureka, 각 서비스)은 로컬 개발 때 IntelliJ에서 실행하고, 측정·배포 때 compose로 띄운다.
- 관리자 UI는 `127.0.0.1:3000:3000`처럼 로컬에만 바인딩하고, 내부 전용 서비스는 `ports` 대신 `expose`만 쓴다.

### 확인 순서
1. `./gradlew build`가 모든 모듈에서 통과한다.
2. `docker compose up`으로 인프라가 뜬다.
3. `http://localhost:8888/user-service/local`이 설정을 돌려준다.
4. eureka → gateway → user-service를 실행하고 Eureka 대시보드에 등록된 것을 본다.

### 각자 세팅한 뒤 합칠 때
- 패키지 이름, 포트, 모듈 이름, 빌드 스크립트 언어(Groovy 또는 Kotlin DSL)가 같아야 차이가 구현에서만 난다.
- 뼈대 파일(`settings.gradle`, 루트 `build.gradle`, wrapper)은 줄 단위로 섞지 않고 한 사람 것을 기준으로 고른다. 공통 모듈 코드는 그 위에서 비교해 좋은 부분을 옮긴다.

## 이 프로젝트에서는
- 기준 문서: `docs/core/SA MASTER.md` 6-2(공통 작업 목록), 9-1(기술 스택·실행 위치), 9-2(포트 노출), 9-6(Config 서버), 11-2(응답 형식·공통 모듈), 13장(모듈·패키지 구조), 17-1(10/27 합의 안건).
- 패키지 이름 `iseonjwa`와 포트는 `(팀 확인)`(U12)이고, Java 21은 팀 합의 전(#30 잠정)이다. 예시의 `com.iseonjwa`는 가정이다.
- `common-messaging`(Outbox·DLQ)은 결제 담당 범위라 공통 환경 세팅에서는 빼도 된다.
- 브랜치 전략은 `docs/guides/git-workflow.md`에 아직 없고 10/27 안건이다.

## 면접 포인트
- 멀티모듈에서 BOM을 루트에 두는 이유: 모든 모듈이 같은 라이브러리 버전을 써서 서비스 사이 버전 불일치를 막는다.
- Config 서버 native 백엔드를 고른 이유: 모노레포 안 폴더라 설정 변경도 코드처럼 PR 리뷰와 git 이력을 탄다. 대가는 컨테이너 하나와 기동 의존성이다.
- Spring Cloud 버전을 Boot 버전에 맞추는 이유: 릴리스 트레인마다 지원하는 Boot 범위가 정해져 있다.

## 참고
- Spring Initializr 메타데이터: https://start.spring.io/actuator/info (2026-10-26 확인)
- `docs/core/SA MASTER.md` v3.1 (Notion export와 내용 동일함을 2026-10-27 확인)
