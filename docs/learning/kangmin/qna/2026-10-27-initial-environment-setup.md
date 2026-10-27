---
title: 초기 개발 환경(M1 뼈대)은 어떻게 구성했고 각 선택의 이유는 무엇인가
date: 2026-10-27
updated: 2026-10-27
asker: GGangMY
level: 중급
tags: [gradle, docker-compose, spring-cloud-config, eureka, gateway, flyway, testcontainers]
---

# 초기 개발 환경(M1 뼈대)은 어떻게 구성했고 각 선택의 이유는 무엇인가

## 질문
초기 환경을 단계별 브랜치(`ggangmy-01` ~ `ggangmy-06`)로 구성했다. 단계마다 무엇을 만들었고, 어떤 선택지를 비교해 왜 골랐는지 다시 보며 공부한다.

## 짧은 답
- Gradle 멀티모듈(Kotlin DSL + 컨벤션 플러그인 + 버전 카탈로그)로 서비스 7개와 공통 모듈 4개를 묶었다. Java 21은 툴체인이 맞춘다.
- `docker compose up` 한 번으로 인프라 9개 + Config 서버 + Eureka + Gateway + 서비스 4개가 순서대로 뜬다. 외부에는 Gateway만 열린다.
- 설정은 `config-repo/` 한 곳에 있고, 서비스는 기동할 때 Config 서버에서 받는다. 비밀값은 `.env`에만 있다.
- 확인한 것: 전체 빌드와 테스트 11개 통과, Eureka 등록 4개, Gateway → 서비스 라우팅이 Zipkin 추적으로 이어짐, Prometheus가 7개 서비스를 수집함, 3개 DB에 Flyway V1이 적용됨.
- 하지 않은 것: 기능 API, JWT, 공통 응답 모듈. 이슈로 나눠 병렬로 진행한다.

## 단계와 커밋

| 단계 | 브랜치 | 커밋 | 만든 것 |
|---|---|---|---|
| 1 | `ggangmy-01-gradle` | `e206e5a` | Gradle 뼈대, Java 21 툴체인, 공통 모듈 4개(빈 모듈) |
| 2 | `ggangmy-02-compose` | `4b7ab82` | 인프라 compose, `.env.example`, Prometheus·Grafana 설정 |
| 3 | `ggangmy-03-config` | `060daf8`, `654a358` | 관측 의존성 컨벤션, Config 서버, `config-repo/`, 공용 Dockerfile |
| 4 | `ggangmy-04-eureka` | `85f5cea` | Eureka 서버 |
| 5 | `ggangmy-05-gateway` | `c9cf5bd`, `db242a5` | 테스트 입력 등록, Gateway 라우팅 |
| 6·7 | `ggangmy-06-services` | `df327d8` ~ `c184dce` | MVC·JPA 컨벤션, 서비스 4개 뼈대, Dockerfile 수정, Gateway 포트 변수 |

모든 단계는 `ggangmy`에 fast-forward로 합쳤다. 원격 push는 하지 않았다.

## 자세히

### 1. Gradle 멀티모듈

| 선택 | 비교한 것 | 고른 이유 | 감수한 점 |
|---|---|---|---|
| Kotlin DSL | Groovy DSL | 타입 검사와 IDE 자동 완성. Spring Initializr 기본값 | 첫 설정 동기화가 느리다 |
| 컨벤션 플러그인(`build-logic/`) | 루트의 `subprojects {}` | 모듈이 필요한 규칙만 골라 쓴다(`java-library`, `spring-boot-service`, `mvc-service`, `jpa-postgres`) | 파일이 늘고 구조를 알아야 고칠 수 있다 |
| 버전 카탈로그 + `platform(BOM)` | `io.spring.dependency-management` 플러그인 | Gradle 표준 기능. 버전은 `libs.versions.toml` 한 곳 | BOM을 `implementation` 등 설정마다 넣어야 한다 |
| foojay 툴체인 | 각자 JDK 설치 | PC에 JDK 21이 없어도 Gradle이 받아 온다 | 첫 빌드에 내려받는 시간 |

컨벤션 플러그인은 계단식이다: `java-conventions`(Java 21, BOM, JUnit) → `spring-boot-service`(bootJar, Actuator, Prometheus, Zipkin) → `mvc-service`(Web MVC, 검증, Config 클라이언트) → `jpa-postgres`(JPA, Flyway, PostgreSQL, Testcontainers). 서비스의 `build.gradle.kts`는 플러그인 하나와 서비스 고유 의존성만 적는다.

### 2. docker compose 인프라

| 선택 | 비교한 것 | 고른 이유 | 감수한 점 |
|---|---|---|---|
| 이미지 태그를 정확한 버전으로 고정(`postgres:18.6-alpine`) | `latest`, 주 버전만(`18-alpine`) | 팀원마다, 날마다 다른 버전이 뜨지 않는다 | 올릴 때 직접 바꿔야 한다 |
| 로컬 전용 포트는 `compose.override.yaml` | 파일 하나에 다 넣기, 프로필 | `docker compose up`은 override를 자동으로 합친다. 배포는 `-f compose.yaml`로 override를 뺀다 | 파일이 둘이다 |
| 열 때는 항상 `127.0.0.1:` 바인딩 | `5433:5432` | override를 서버에서 실수로 읽어도 외부로 열리지 않는다(SA 9-2 이중 방어) | 다른 PC에서 내 DB에 붙을 수 없다(의도) |
| Kafka 리스너 2개(`kafka:29092`, `localhost:9092`) | 리스너 1개 | 컨테이너와 IntelliJ 앱이 각자 맞는 주소를 받는다 | SA 표의 9092는 PC 기준이고, 컨테이너끼리는 29092다 |
| Kafka 토픽 자동 생성 끔 | 켬(기본) | 오타 토픽이 조용히 생기지 않는다. 확인: 없는 토픽에 보내면 `UNKNOWN_TOPIC_OR_PARTITION` | 토픽은 코드나 명령으로 만들어야 한다 |
| Redis `noeviction` + 영속화 끔 | `allkeys-lru`, AOF | 메모리가 차면 대기열 키를 몰래 지우지 않고 쓰기를 실패시킨다. Redis는 기준 저장소가 아니다(SA 9-5) | 재시작하면 대기열이 비워진다 |
| `${VAR:?메시지}` | `${VAR}` | `.env`가 없으면 빈 비밀번호로 뜨지 않고 바로 멈춘다 | 처음 받은 팀원은 `.env`부터 만들어야 한다 |

PostgreSQL 18부터 데이터 경로가 `/var/lib/postgresql/18/docker`로 바뀌어서 볼륨을 `/var/lib/postgresql`에 마운트한다.

### 3. Config 서버와 config-repo

| 선택 | 비교한 것 | 고른 이유 |
|---|---|---|
| native(파일) 백엔드 | Git 백엔드 | 모노레포의 `config-repo/`를 마운트만 한다. 설정 변경도 PR로 리뷰된다 (SA 9-6) |
| 클라이언트는 `optional:configserver:` + `fail-fast: true` | `configserver:`만 | test 프로필에서 Config를 끌 수 있고(TS-2), 실제 기동에서는 Config 서버가 없으면 멈춘다 |
| 서비스 모듈의 `application.yml`에는 이름과 import만 | 모듈마다 설정 | 설정이 한 곳에만 있다 |

**우선순위(테스트로 확인)**: `{서비스}-{프로필}` > `application-{프로필}` > `{서비스}` > `application`. 프로필 파일이 서비스 이름 파일보다 앞선다. 처음에 반대로 알고 README를 썼다가 `ConfigRepoTest`가 잡아냈다. 서비스마다 다른 값을 `application-local.yml`에 두면 모든 서비스의 같은 키를 덮어쓴다.

비밀번호는 `config-repo`에 `${USER_DB_PASSWORD}`로만 있다. Config 서버는 자리표시자를 그대로 내려 주고, 서비스 컨테이너의 환경 변수로 풀린다. 확인: Config 서버 응답에 실제 비밀번호가 0번 나온다.

### 4. Eureka
- 서버 1대라 자기 등록과 목록 받기를 끈다. 개발 중 재시작이 잦아 죽은 인스턴스를 10초마다 정리한다.
- Eureka도 Config 클라이언트로 만들어 "서비스가 설정을 받는 방식"을 먼저 검증했다. 포트 8761은 `config-repo/eureka.yml`에 있으므로, 8761로 떴다는 것이 설정을 받았다는 증거다.

### 5. Gateway
- 라우트는 `config-repo/gateway.yml`. Spring Cloud Gateway 5부터 속성 접두사가 `spring.cloud.gateway.server.webflux.*`로 바뀌었다(jar 메타데이터로 확인).
- `lb://서비스이름`이면 Eureka에서 인스턴스를 찾는다. 서비스가 막 등록된 직후에는 Gateway 쪽 목록 캐시가 갱신되지 않아 30초가량 503이 날 수 있다(실제로 한 번 봤다).
- mock-pg는 외부 PG 역할이라 라우트도 Eureka 등록도 없다. payment-service가 `iseonjwa.pg.base-url`로 부른다.
- 응답 시간 상한 3초(SA 9-4)를 `response-timeout`으로 걸었다.
- `filter/`, `config/` 패키지는 비워 두고 무엇이 들어갈지 `package-info.java`에 적었다(JWT, 헤더 전달, 블랙리스트, 요청 제한, 실패 응답 형식).

### 6. 서비스 뼈대와 패키지
- user, payment, mock-pg: `presentation / application / domain / infrastructure / global`.
- reservation: 모듈 5개(`concert`, `queue`, `reservation`, `lottery`, `notification`) × 4계층 + `global`. 결정 기록 #9대로 나중에 패키지 단위로 떼어 낼 수 있게 했다.
- 빈 폴더는 git에 남지 않아서 패키지마다 `package-info.java`에 계층 규칙을 한 줄로 적었다.
- JPA는 `ddl-auto: validate`, `open-in-view: false`. 테이블은 Flyway 마이그레이션으로만 만든다. `V1__baseline.sql`은 기준점이고 기능 이슈에서 V2부터 추가한다.
- 테스트는 Testcontainers PostgreSQL 18.6(compose와 같은 버전)에 붙는다. H2는 부분 인덱스(#58) 같은 PostgreSQL 문법을 확인할 수 없다. `@ServiceConnection`이 컨테이너 주소를 datasource 설정으로 넘긴다.

### 7. 공용 Dockerfile
- 멀티 스테이지: JDK 이미지에서 `./gradlew bootJar`로 모든 jar를 만들고, JRE 이미지에 해당 서비스 jar 하나만 복사한다. root가 아닌 사용자로 실행한다.
- 서비스마다 따로 빌드하면 Gradle 캐시 잠금이 충돌해서(TS-3), 빌드 단계를 공유하게 바꿨다.
- 비교: Jib, `bootBuildImage`(Buildpacks)도 있다. Dockerfile은 플러그인 없이 무엇이 들어가는지 그대로 보인다는 점 때문에 골랐다.

## 이 프로젝트에서는

직접 해 볼 명령(저장소 루트, Git Bash):

```bash
cp .env.example .env
```

```bash
docker compose up -d --build --wait
```

```bash
curl -s -H "Accept: application/json" localhost:8761/eureka/apps
```

```bash
curl -s localhost:8888/user-service/local
```

```bash
./gradlew build
```

| 화면 | 주소 |
|---|---|
| Eureka | http://localhost:8761 |
| Kafka UI | http://localhost:8085 |
| Prometheus Targets | http://localhost:9090/targets |
| Grafana (admin / `.env` 값) | http://localhost:3000 |
| Zipkin | http://localhost:9411 |

IntelliJ로 서비스 하나만 띄울 때는 실행 설정에 프로필 `local`과 DB 비밀번호 환경 변수(`USER_DB_PASSWORD` 등)를 넣는다. compose의 같은 서비스는 `docker compose stop user-service`로 멈춘다.

측정값(2026-10-27, 대기 상태, `docker stats`): 서비스 210~390MiB, kafka 630MiB, grafana 309MiB / 상한 384MiB(80%). grafana 상한은 부하 중에 다시 본다.

## 확인할 질문
- Prometheus가 `/actuator/health`와 `/actuator/prometheus` 호출까지 추적해 Zipkin에 쌓인다. 추적에서 actuator를 뺄지 정해야 한다.
- 컨테이너 메모리 512m 이하에서는 JVM이 Serial GC를 고른다(config-server 지표에서 `Eden Space`로 확인). 서비스별 GC를 정할지 부하 측정 때 본다.
- `.env`를 IntelliJ 실행 설정과 어떻게 공유할지(EnvFile 플러그인 등) 팀 규칙이 필요하다.
- ArchUnit으로 계층 의존 규칙(domain → infrastructure 금지)을 테스트로 강제할지 정한다.

## 면접 포인트
- "설정은 왜 Config 서버로?": 숫자 기준과 loadtest 프로필 전환을 한 곳에서 바꾸려고. 대가는 기동 순서 의존이며, healthcheck + `depends_on: service_healthy`로 순서를 보장했다.
- "테스트는 어떻게 믿나?": 변이 테스트를 했다. 설정 파일을 틀리게 바꾸고, 의존성을 빼고, 비밀번호를 평문으로 넣어 테스트가 실패하는지 봤다. 그 과정에서 Gradle이 테스트를 건너뛰던 문제(TS-1)와 우선순위 착각을 찾았다.
- "로컬과 배포의 포트 노출 차이는?": override 파일로 로컬 포트를 열되 모두 `127.0.0.1`에만 바인딩해, 서버에서 실수로 읽어도 외부로 열리지 않게 했다.

## 참고
- SA MASTER 9-1 ~ 9-6, 13장
- `docs/troubleshooting.md` TS-1 ~ TS-4
- Spring Boot 4.1.2 BOM, Spring Cloud 2025.1.4 BOM(Maven Central), 각 jar의 `spring-configuration-metadata.json`
