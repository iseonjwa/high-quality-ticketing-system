# 실행 환경 가이드

M1 공통 환경(공통 모듈, Config 서버, Eureka, Gateway, 인프라)을 띄우고 확인하는 방법이다. 근거는 SA MASTER v3.1 6-2, 9-1~9-6, 11-2, 13장이다. hyoseung이 각자 만들기(10/27)로 만든 안이며, 팀이 합칠 때 바뀔 수 있다.

## 1. 구성

| 구분 | 모듈·서비스 | 내용 |
|---|---|---|
| 공통 모듈 | `common` | `BaseEntity`(감사 컬럼), `HeaderAuditorAware`(`X-User-Id` → created_by), `EventEnvelope`(Kafka 공통 봉투) |
| | `common-response` | `SuccessResponse`, `ErrorResponse`, `ErrorCode`, `CommonErrorCode`, `BusinessException`. Spring Web 의존 없음(Gateway도 씀) |
| | `common-web` | MVC 전역 예외 처리, `/error` 대체, 성공 응답 `traceId` 채우기. 의존성만 추가하면 자동 적용 |
| 기반 | `config-server` | native 백엔드, `config-repo/`를 읽음 (8888) |
| | `eureka` | 서비스 등록 (8761) |
| | `gateway` | 라우팅, JWT 검증, 헤더 위조 제거, 블랙리스트(Redis, fail-open), 실패 응답 형식 (8080) |
| 서비스 | `user-service` | Config·Eureka 연결까지만. 회원·인증 기능은 아직 없다 (8081) |
| 인프라 | compose | PostgreSQL 3개, Redis, Kafka(KRaft), Kafka-UI, Prometheus, Grafana, Zipkin |

`common-messaging`(Outbox·DLQ)은 결제 담당 범위라 넣지 않았다. 대기열 입장 토큰 필터, 사용자 요청 제한은 M2 이후다.

## 2. 처음 한 번

1. JDK 21, Docker Desktop을 준비한다. IntelliJ의 Gradle JVM도 21로 둔다.
2. `.env`를 만든다. 커밋하지 않는다.
   ```bash
   cp .env.example .env
   # JWT_SECRET 등은 openssl rand -base64 32 로 만든 값을 넣는다
   ```
3. 빌드와 테스트: `./gradlew build`

## 3. 띄우는 방법

| 목적 | 명령 | 뜨는 것 |
|---|---|---|
| 로컬 개발 (앱은 IntelliJ) | `docker compose up -d` | 인프라 + config-server |
| 전체 (M1 걷는 뼈대, 측정) | `docker compose --profile app up -d --build` | 위 + eureka, gateway, user-service |
| 내리기 | `docker compose --profile app down` | 데이터는 볼륨에 남는다. 지우려면 `down -v` |

- 기동 순서는 healthcheck로 맞춘다: config-server → eureka → 서비스 (SA 9-6).
- 앱 이미지는 루트 `Dockerfile` 하나로 만든다. compose가 `MODULE` 인자로 모듈을 고른다.
- 배포(EC2)에서는 로컬용 포트를 여는 `compose.override.yaml`을 빼고 `docker compose -f compose.yaml --profile app up -d`로 띄운다.

### IntelliJ로 앱을 띄울 때
- 순서: (compose로 인프라) → `EurekaApplication` → `UserServiceApplication` → `GatewayApplication`
- Working directory는 모듈 폴더(기본값)로 둔다. gateway·user-service가 `../.env`를 읽어 `JWT_SECRET` 같은 값을 얻는다.
- 앱 일부만 컨테이너로 띄우고 나머지를 IntelliJ로 띄우면 안 된다. 컨테이너 앱은 Eureka에 컨테이너 내부 IP로 등록돼 PC에서 닿지 않는다(503).

## 4. 포트 (SA 9-1, 9-2)

| 서비스 | 컨테이너 안 | PC에서 | 공개 범위 |
|---|---|---|---|
| gateway | 8080 | `localhost:8080` | 전체 (유일한 진입점) |
| eureka | 8761 | `127.0.0.1:8761` | 이 PC만 |
| grafana | 3000 | `127.0.0.1:3000` (admin / `.env`) | 이 PC만 |
| kafka-ui | 8080 | `127.0.0.1:8085` | 이 PC만 |
| prometheus | 9090 | `127.0.0.1:9090` | 이 PC만 |
| zipkin | 9411 | `127.0.0.1:9411` | 이 PC만 |
| config-server | 8888 | `127.0.0.1:8888` (override) | 로컬 개발만 |
| postgres user·reservation·payment | 5432 | `127.0.0.1:5433·5434·5435` (override) | 로컬 개발만 |
| redis | 6379 | `127.0.0.1:6379` (override) | 로컬 개발만 |
| kafka | 9092 (내부) | `127.0.0.1:29092` (override) | 로컬 개발만 |
| user-service | 8081 | 열지 않음 | compose 안만 |

포트 번호는 SA에서 아직 `(팀 확인)`(U12)이다. 위 값은 SA 9-1 구성도의 번호다.

## 5. Gateway가 하는 일

1. 클라이언트가 보낸 `X-User-Id`·`X-User-Role`을 지운다.
2. 인증 없이 통과: `POST /api/v1/users`, `POST /api/v1/auth/login`, `POST /api/v1/auth/refresh`, `GET /api/v1/concerts/**`
3. Access 토큰 검증: 없음·위조 → 401 `UNAUTHORIZED`, 만료 → 401 `TOKEN_EXPIRED`
4. 블랙리스트 `auth:blacklist:{jti}` 확인 → 401 `TOKEN_REVOKED`. Redis 호출은 100ms 타임아웃과 서킷 브레이커(최근 20회 중 50% 실패 또는 50ms 초과 시 OPEN)로 감싸고, 실패하면 통과시킨다(fail-open, SA 9-5)
5. `X-User-Id`·`X-User-Role`을 붙여 라우팅. 운영자 권한(ADMIN)과 본인 확인은 각 서비스가 한다.

라우트: `/api/v1/users/**`·`/api/v1/auth/**` → user-service, `/api/v1/concerts|schedules|reservations|lotteries|notifications|admin/**` → reservation-service, `/api/v1/payments/**` → payment-service. 아직 없는 서비스로 가는 요청은 503 `SERVICE_UNAVAILABLE`이다.

### user-service가 발급할 Access 토큰 (팀 합의 필요)
Gateway는 아래 형식만 받는다. 인증 기능을 만드는 사람과 맞춰야 한다.
- 알고리즘 HS256, 키는 `.env`의 `JWT_SECRET`을 **Base64 디코딩한 바이트**(32바이트 이상)
- claim: `sub` = users.id(숫자 문자열), `role` = `USER` | `ADMIN`, `jti` = 토큰 ID(로그아웃 블랙리스트 키), `exp` = 발급 후 30분(SA 9-4)

## 6. 확인 방법

```bash
curl localhost:8888/gateway/default                 # Config 서버가 설정을 준다
curl -H 'Accept: application/json' localhost:8761/eureka/apps   # GATEWAY, USER-SERVICE 등록
curl -i localhost:8080/api/v1/users/me              # 401 UNAUTHORIZED, 실패 형태 + traceId
curl -i -X POST localhost:8080/api/v1/users         # user-service까지 도착 (기능이 없어 404 NOT_FOUND)
```
- 서비스가 막 뜬 직후 30초 정도는 503이 날 수 있다. Gateway가 Eureka 목록을 30초마다 새로 받기 때문이다.
- Prometheus(`127.0.0.1:9090` → Status → Targets)에서 gateway·user-service·config-server·eureka가 UP이면 지표 수집이 된다.
- Zipkin(`127.0.0.1:9411`)에서 응답의 `traceId`로 검색하면 gateway → user-service 구간이 한 트레이스로 보인다.

## 7. 새 서비스를 붙일 때

1. `settings.gradle`에 `include`, 모듈 `build.gradle`에 `plugins { id 'org.springframework.boot' }`와 `common-web`·config·eureka-client 의존성
2. `src/main/resources/application.yml`에 이름과 `spring.config.import`(user-service와 같은 형식), `src/test/resources/application.yml`에 Config·Eureka 끄기
3. `config-repo/{서비스}.yml`에 포트와 DB 설정
4. `compose.yaml`에 서비스 추가(`<<: *spring-app`, `MODULE`, 메모리 상한 SA 9-3)

Actuator·Prometheus·Zipkin 의존성은 루트 `build.gradle`이 실행 모듈마다 넣는다.

## 8. 이 안의 선택 (다른 안과 비교할 때)

팀은 각자 만든 안을 비교해 합친다(10/27). 비교할 때 볼 이 안의 선택과 이유다.

| 영역 | 이 안의 선택 | 이유 | 다른 선택지 |
|---|---|---|---|
| 빌드 스크립트 | Groovy DSL, 루트 `build.gradle`의 `subprojects`에 공통 설정 | 팀원 대부분이 Groovy에 익숙하다. 파일 하나만 보면 전체 규칙이 보인다 | Kotlin DSL + `build-logic` 컨벤션 플러그인, 버전 카탈로그(`libs.versions.toml`) |
| 관측 의존성 | Boot 플러그인을 붙인 모듈에 Actuator·Prometheus·Zipkin을 루트가 자동으로 넣음 | 새 서비스가 빠뜨리지 않는다 | 모듈마다 직접 적기 |
| 응답 형식 적용 | `common-web`을 자동 설정으로 만들어 의존성만 추가하면 적용 | M1 완료 기준("가져다 쓰면 그대로 적용") | 서비스가 `@Import`하거나 패키지를 스캔 |
| 상태 → 에러 코드 | `CommonErrorCode.fromStatus` 하나를 MVC·`/error`·Gateway가 함께 씀 | 세 곳이 같은 코드를 낸다 | 곳마다 따로 매핑 |
| traceId | MVC는 MDC, Gateway는 요청의 관측 컨텍스트에서 꺼냄 | WebFlux는 스레드가 바뀌어 MDC를 믿을 수 없다 | 헤더 전파, Tracer 직접 주입 |
| 설정 위치 | 서비스 설정은 `config-repo`, 주소는 환경 변수 자리표시자(`${EUREKA_URL:...}`) | 로컬·compose가 같은 파일을 쓰고 프로필이 늘지 않는다 | `docker` 같은 별도 프로필 |
| 로컬 비밀값 | IntelliJ 앱이 `../.env`를 직접 읽음(`optional:file:../.env[.properties]`) | 실행 설정마다 환경 변수를 넣지 않아도 된다 | IntelliJ Run Configuration에 환경 변수 |
| Eureka | Config 클라이언트가 아님 | 기동 순서가 config-server → eureka라 묶을 이유가 없다 | Eureka도 Config에서 설정 받기 |
| Gateway 인증 | 전역 필터 하나: 헤더 위조 제거 → 공개 경로 → JWT → 블랙리스트 → 헤더 추가. 공개 경로는 설정 파일 | 순서가 한 클래스에 보인다 | 경로 정책 클래스 분리, 라우트별 필터 |
| 운영자 권한 | Gateway는 확인하지 않고 서비스가 확인 | SA 9-2 요청 차단 계층("서비스: 권한") | Gateway에서 `/admin/**` ADMIN 확인 |
| 블랙리스트 Redis | 100ms 타임아웃 + 서킷 브레이커, 실패 시 통과(fail-open). 기준값은 `gateway.redis-guard` | SA 9-4·9-5 그대로, 측정 후 설정만 바꾼다 | fail-closed |
| compose | 파일 하나 + 로컬 포트용 override, 앱은 `app` 프로필 | 로컬 개발(인프라만)과 전체 실행을 같은 파일로 | 용도별 compose 파일 여러 개 |
| 앱 이미지 | 루트 Dockerfile 하나, `MODULE` 인자 | 서비스가 늘어도 Dockerfile이 늘지 않는다 | 모듈마다 Dockerfile |
| 지표 수집 대상 | Prometheus가 Eureka에서 앱을 찾음 | 새 서비스가 등록만 하면 수집된다 | 정적 대상 목록 |

이 안에 없는 것: 회원·인증 기능, `common-messaging`, reservation·payment·mock-pg 모듈, `/internal/**` 차단, CI, DB 마이그레이션(Flyway).
