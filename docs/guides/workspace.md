# Spring Boot 워크스페이스와 M1 인증

ISE-5에서 전체 실행 기반과 인증을 구성했다. 업무 서비스는 User·Reservation·Payment 3개이며 Config Server 추가는 Junhyun An의 지시다. 팀 전체 합의와 구분한다. 공통 모듈의 사용 계약은 [공통 모듈 가이드](common-modules.md)에 있다.

## 구성

Java 21, Gradle Wrapper 9.1.0, Spring Boot 4.1.2, Spring Cloud 2025.1.4를 사용한다. 인프라 이미지는 compose.yml과 Dockerfile의 digest로 고정했다. 공통 라이브러리에는 실행 JAR를 만들지 않고 Boot 앱에는 app.jar를 만든다.

`system-tests`는 Java·JUnit 검증, `workspace-tools`는 환경·프로세스·포트 도구와 그 회귀 테스트를 제공한다. 둘 다 실행 서비스가 아니다. Python 설치는 필요 없다. 검증의 위치·실행 범위는 [테스트 가이드](testing.md)를 따른다.

| 앱 | 업무 포트 | local override 관리 포트 | 역할 |
|---|---|---|---|
| config-server | 8888 | 19888 | config-repo의 native 설정 공급 |
| eureka | 8761 | 18761 | 서비스 등록·탐색 |
| gateway | 8080 | 18080 | WebFlux 라우팅·JWT·권한·블랙리스트 |
| user-service | 8081 | 18081 | 가입·로그인·재발급·로그아웃·내 정보 |
| reservation-service | 8082 | 18082 | 예약 영역 실행 기반, local 연결 진단 |
| payment-service | 8083 | 18083 | 결제 영역 실행 기반, local 연결 진단 |
| mock-pg | 8090 | 18090 | Mock PG 실행 기반 |

컨테이너 안의 관리 포트는 공통으로 9080이며 서로 다른 컨테이너다. 기본 Compose는 서비스·관리 포트를 호스트에 열지 않는다. Gateway와 관리자 UI만 loopback에 바인딩한다. local override에서 개발용 서비스·관리·DB·Redis·Kafka 포트를 loopback에 연다.

공통 코드는 `common` 모듈 하나에 패키지로 나뉜다([ADR 0013](../decisions/0013-single-common-module.md)). `persistence`·`event`는 감사 엔티티·이벤트 봉투, `response`·`identity`는 Spring에 독립적인 응답·에러 계약과 사용자 헤더·역할 상수, `auth`는 Gateway와 User가 함께 쓰는 JWT 서명·검증 계약, `web`은 MVC 예외·내부 사용자 컨텍스트, `messaging`은 Boot Kafka 연결 기반이다. 웹·JPA·Kafka·JWT 라이브러리는 `compileOnly`라서 쓰는 서비스가 직접 선언한다. Gateway는 `auth`·`response`·`identity`만 쓰며 MVC·JPA·Kafka를 받지 않는다(`GatewayClasspathTest`).

## 서비스 포트 비공개 전제

서비스는 Gateway가 검증해 넣은 X-User-Id·X-User-Role을 서명 없이 믿는다(SA 7-2). 그래서 Gateway 외의 포트가 외부에서 닿으면 누구나 사용자·ADMIN을 위조할 수 있다. 이 전제를 지키는 규칙은 다음과 같다.

- 기본 Compose는 Gateway(8080)와 관리자 UI(Grafana·Kafka-UI·Prometheus·Zipkin)만 호스트에 열고, 모두 `127.0.0.1`에 바인딩한다. 서비스·Config Server·Eureka·Mock PG·DB·Redis·Kafka는 열지 않는다.
- local override와 JVM 실행의 개발용 포트도 모두 `127.0.0.1`이다.
- 배포(EC2)에서는 보안그룹으로 8080만 외부에 열고(부하 측정 중에는 측정 기기 IP로 제한) 22번은 팀원 IP만 허용한다. 나머지는 SA 9-2의 "허용 없음"을 따른다.
- 서비스 사이의 내부 HTTP API는 `/internal/**`(또는 `/api/v1/internal/**`)에 둔다. Gateway의 `AccessPolicy`가 이 경로를 외부 요청에서 404로 막는다.
- `./gradlew checkPortExposure`가 위 규칙(기본 Compose의 공개 서비스 목록, 모든 호스트 주소가 loopback)을 검사한다. `verifyWorkspace`와 CI에 포함되며 규칙 위반이면 실패한다.

## 전체 Compose 실행

Docker와 JDK 21을 준비하고 저장소 루트에서 실행한다. 다른 프로젝트 컨테이너를 이 명령으로 중지하지 않는다.

```bash
./gradlew initLocalEnv
docker compose -f compose.yml -f compose.local.yml up -d --build --wait --wait-timeout 180
./gradlew :system-tests:systemTest -PredisFailure=true --no-daemon
```

환경변수 생성기는 .env가 있으면 덮어쓰지 않는다. 그래서 `PAYMENT_TOKEN_SECRET`이 생기기 전에 만든 .env에는 키가 없다. 이 경우 `openssl rand -hex 32`로 만든 값을 `PAYMENT_TOKEN_SECRET=`에 직접 넣는다(없으면 compose가 시작 전에 오류로 멈춘다). 새 파일은 0600으로 만들고 값을 출력하지 않는다. 변수 목록은 .env.example에 있다. 실제 키·비밀번호는 .env에만 두고 커밋하지 않는다. Config Server에는 이 비밀값을 전달하지 않고 각 클라이언트 프로세스의 환경변수로 주입한다.

JVM runner와 Java 시스템 테스트는 컨테이너 생성 없이 Compose의 dotenv 해석 결과를 사용한다. 따옴표·주석·이스케이프·보간을 지원하고, 쉘 환경변수가 .env보다 우선하는 규칙을 유지한다. 값은 출력하지 않는다.

Config Server → Eureka → 나머지 앱 순서로 기동한다. Kafka는 볼륨 최상위 소유권을 맞추는 init 컨테이너가 성공한 뒤 일반 사용자로 실행한다. init 컨테이너의 종료 코드 0은 정상이다.

```bash
docker compose -f compose.yml -f compose.local.yml ps
docker compose -f compose.yml -f compose.local.yml logs --tail=50 gateway user-service
curl -fsS http://localhost:18080/actuator/health/readiness
```

모든 구성은 이름 iseonjwa 아래에 격리된다. 일반 종료는 다음 명령을 쓴다. 볼륨 삭제 옵션은 넣지 않는다.

```bash
docker compose -f compose.yml -f compose.local.yml stop
```

## 인프라 Compose + 앱 JVM 실행

전체 Compose 앱이 실행 중이면 위 stop 명령으로 이 프로젝트만 멈춘 뒤 시작한다. infra만 선택해 올린다.

```bash
docker compose -f compose.yml -f compose.local.yml -f compose.jvm.yml up -d postgres-user postgres-reservation postgres-payment redis kafka kafka-ui prometheus grafana zipkin
./gradlew runLocal -Papp=config-server
```

Config Server가 준비되면 다른 터미널에서 Eureka, 이어서 나머지 앱을 각각 실행한다. `runLocal`은 선택한 앱의 JAR를 준비하고 필요한 개인 환경변수와 loopback 주소·관리 포트로 실행한다. 종료 시 자신이 시작한 JVM을 정리한다.

```bash
./gradlew runLocal -Papp=eureka
./gradlew runLocal -Papp=gateway
./gradlew runLocal -Papp=user-service
./gradlew runLocal -Papp=reservation-service
./gradlew runLocal -Papp=payment-service
./gradlew runLocal -Papp=mock-pg
```

IntelliJ에서는 settings.gradle을 Gradle 프로젝트로 열고 Gradle JVM과 프로젝트 SDK를 21로 맞춘다. Gradle `runLocal` 태스크를 사용하거나 `LocalApps`와 같은 환경을 Run Configuration에 설정한다. IntelliJ가 .env를 자동으로 읽는다고 가정하지 않는다. 공통 라이브러리에는 실행 진입점을 추가하지 않는다.

local 프로필과 Config Server의 기본 업무·관리 주소는 127.0.0.1이다. runner는 SERVER_ADDRESS·MANAGEMENT_SERVER_ADDRESS를 고정하고 Eureka에도 EUREKA_INSTANCE_IPADDRESS=127.0.0.1로 등록한다. Compose는 내부 통신을 위해 주소 0.0.0.0과 컨테이너 IP 등록을 명시하며, 호스트 포트 공개는 계속 loopback으로 제한한다.

JVM 모드의 Docker Prometheus는 host.docker.internal을 통해 관리 포트를 읽는다. macOS Docker Desktop에서 loopback 접근과 앱 7개 수집을 확인했다. IntelliJ 수동 설정에서도 업무·관리 바인딩과 Eureka 등록 주소를 함께 맞춘다.

프로필은 local·test·loadtest·prod로 구분한다. Config Server는 native 프로필로 시작한다. test 프로필은 원격 Config import를 하지 않고 테스트 DB·Redis를 사용한다. 일반 실행은 Config를 필수로 읽고 제한된 재시도 뒤 실패한다. 설정 파일 변경 후 클라이언트를 재시작한다. refresh·Bus는 없다.

## 인증 계약

SA의 /api/v1 경로를 사용한다. 응답은 SA v3.1(#19)의 성공 `{status, message, data, traceId}`·실패 `{status, code, message, errors, traceId}` 형태다([공통 모듈 가이드](common-modules.md) 참고). /users 가입은 201, /auth/login·/auth/refresh·/auth/logout·/users/me는 성공 시 200이다. 로그아웃 data는 NULL이다. 가입의 role은 USER로 고정하며 ADMIN은 개인 환경변수의 시드 계정으로만 만든다. 기존 운영자 계정의 비밀번호는 재기동 때 덮어쓰지 않는다.

Access는 30분, Refresh는 14일이다. 두 서명 키를 분리하고 HS256을 사용한다. issuer는 iseonjwa-auth, Access audience는 iseonjwa-api, Refresh audience는 user-service다. sub·role·jti·iat·exp·token_type을 검증한다. Refresh를 Access로 사용하거나 반대로 사용할 수 없다.

Access·Refresh 만료 초와 `auth.max-login-failures`는 양수여야 한다. 0·음수이면 User 서비스 빈 생성 단계에서 기동을 실패시켜 첫 인증 요청까지 설정 오류가 미뤄지지 않게 한다.

Redis의 auth:refresh:{userId}에는 사용자당 하나의 Refresh SHA-256 해시를 저장한다. 새 로그인은 이전 Refresh를 대체한다. 재발급은 Lua 비교·교체로 동시 요청 중 하나만 성공한다. 로그아웃은 해당 사용자의 Refresh를 제거하고 요청 Access jti를 남은 시간 동안 블랙리스트에 넣는다. 다른 기발급 Access를 일괄 폐기하는 기능은 없다.

재발급은 활성 Refresh 확인 뒤 현재 계정 상태를 검사한다. 폐기된 Refresh는 401, 잠긴 계정은 423, 비밀번호 변경이 필요한 계정은 403이며 거절한 세션은 회전하지 않는다. 정상 재발급은 활성 세션 조회와 Lua 비교·교체를 수행한다. 이 추가 조회는 Gateway의 요청별 인증 경로에는 들어가지 않는다.

Gateway는 클라이언트의 X-User-Id·X-User-Role을 항상 지우고 검증한 토큰의 값만 다시 넣는다. 인증이 필요한지와 관리자 경로인지는 Gateway의 `AccessPolicy.of` 한 곳에서만 판정한다. 공개 경로가 아니면 로그인이 필요하고, `/api/v1/admin/**`는 ADMIN만 허용한다. 판정은 라우터와 같은 PathPattern과 이미 파싱된 경로를 쓰므로 관리자 루트·matrix parameter·인코딩 변형도 라우터가 보는 경로로 판정하고, 요청마다 정규식이나 목록을 만들지 않는다.

MVC 서비스는 경로로 판단하지 않는다. `GatewayIdentityFilter`는 사용자 헤더가 있으면 형식만 검증해 요청 속성으로 옮기고, 사용자 정보가 필요한 컨트롤러는 속성이 없으면 401을 받는다. 역할 확인은 서비스에서 `@RequireRole`(`common`의 `web` 패키지)로 선언한다. 사용자 역할 속성이 없으면 401, 허용한 역할이 아니면 403이다. 비밀번호 초기화 같은 관리자 API는 Gateway가 막은 뒤에도 서비스에서 한 번 더 확인한다. SA 7-2대로 서비스는 Gateway가 검증해 전달한 헤더를 신뢰한다. 서명이 없는 헤더라서 서비스 포트를 외부에 공개하지 않는 것이 전제다. 서비스 직접 검증·비대칭 서명은 MVP 이후로 미뤘고, 보강 후보는 [ADR 0008](../decisions/0008-internal-trust-and-auth-design.md)에서 설계 중이다. 직접 공개하는 구조가 아니며 로컬 포트는 개발용이다. 보안 필터 오류도 6키 응답이다.

가입·로그인은 UTF-8 72바이트를 초과하면 400 VALIDATION_FAILED로 거절하며, 한글 등 다중 바이트 문자도 같은 기준을 쓴다.

### 로그인 잠금과 비밀번호 초기화

- 로그인에 5회 연속 실패하면(`auth.max-login-failures`) 계정이 잠기고 423 ACCOUNT_LOCKED를 반환한다. 잠긴 계정은 올바른 비밀번호도 거절한다. 존재하지 않는 이메일은 같은 BCrypt 비교를 거쳐 401을 반환하므로 응답 시간으로 계정 존재를 알기 어렵다.
- 실패 횟수와 잠금은 PostgreSQL `users`에 UPDATE 한 문장으로 기록한다. 동시 시도에도 정확하고 Redis를 잃어도 풀리지 않는다. 로그인에 성공하면 횟수를 0으로 되돌린다.
- 잠금은 운영자의 비밀번호 초기화로만 푼다. `POST /api/v1/admin/users/{userId}/password-reset`(ADMIN)이 16자 임시 비밀번호를 만들어 응답으로 한 번만 돌려주고(`Cache-Control: no-store`), 실패 횟수·잠금을 풀고, 해당 사용자의 Refresh를 폐기한다. 이미 발급한 Access는 만료까지 유효하다.
- 임시 비밀번호로 로그인하면 토큰 없이 403 PASSWORD_CHANGE_REQUIRED를 받는다. `POST /api/v1/auth/password-change`(공개)에 이메일·현재(임시) 비밀번호·새 비밀번호를 보내 바꾼 뒤 로그인한다. 새 비밀번호는 8~72바이트이고 현재와 달라야 한다. 이 엔드포인트도 같은 실패 횟수·잠금을 적용한다. 일반 사용자의 자발적 비밀번호 변경도 같은 엔드포인트를 쓴다.
- 운영자 계정이 잠기면 다른 운영자가 초기화하거나 User 서비스를 재기동한다. 재기동 시 시드 운영자의 잠금만 풀리고 비밀번호는 유지한다.
- 한계: 잠금은 계정 기준이라 남의 이메일로 5회 실패시켜 잠글 수 있다(IP 기준 제한 없음). 같은 이유로 로그인 API가 계정 잠금 여부를 알려 준다. 신뢰 모델 설계에서 함께 다룬다.

Redis 인증 조회는 100ms 타임아웃과 Resilience4j 서킷을 사용한다. 장애·OPEN이면 503과 Retry-After를 반환한다. 이 차단 정책은 [ADR 0005](../decisions/0005-auth-redis-fail-closed.md)의 잠정 구현이며 팀 논의가 필요하다. Redis 명령 타임아웃 뒤에도 서버 측 실행이 완료될 수 있으므로 실패 응답이 변경 전 상태를 보장하지 않는다. 복구 검증은 새 로그인으로 진행한다.

Gateway는 준비 완료 전에 Redis PING으로 실제 연결을 예열한다. 시도당 2초, 최대 3회, 재시도 간격 200ms이며 PONG을 받지 못하면 기동에 실패한다. 기동 후 요청별 100ms 제한과 런타임 장애 처리는 그대로다.

입장·결제 토큰, 대기열·예약·결제 업무, Mock PG 승인·환불, Outbox·재시도·DLQ 처리 로직은 아직 없다. local 전용 진단 API는 identity와 bootstrap.integration 이벤트 송수신만 확인한다. loadtest·prod에서는 진단 컨트롤러가 생성되지 않는다.

## 관측성

- [Grafana](http://localhost:3000): admin 계정, 비밀번호는 개인 .env. 기본 대시보드 uid는 iseonjwa-bootstrap이다.
- [Prometheus](http://localhost:9090/targets): 앱 7개의 별도 관리 포트를 수집한다.
- [Zipkin](http://localhost:9411): Gateway와 서비스의 같은 trace를 확인한다.
- [Kafka-UI](http://localhost:8085): iseonjwa 클러스터를 확인한다.

local 추적은 100%, loadtest는 1%, prod는 10% 샘플링한다. 대시보드는 요청률·5xx·JVM 메모리·인증 Redis 서킷을 보여 준다. 메모리 제한은 로컬 기동용이며 운영 수용량을 뜻하지 않는다.

## 검증

```bash
./gradlew verifyWorkspace --no-daemon
./gradlew :system-tests:systemTest -PredisFailure=true -PcoldStart=true --no-daemon
```

`verifyWorkspace`는 격리된 iseonjwa-test PostgreSQL·Redis를 준비하고 포트 규칙·전체 빌드·JUnit·기록·diff를 검사한다. 테스트 DB는 tmpfs이며 업무 DB 볼륨을 사용하지 않는다. CI에서도 같은 태스크를 사용한다. 로컬 테스트 인프라는 다음 명령으로 중지할 수 있다.

```bash
docker compose -f compose.test.yml stop
```

기본 검사에는 Java 도구의 dotenv·파일 권한·실행 환경·포트·종료 회귀, 모듈·DB·Redis 통합, Config 불가 시 기동 실패를 포함한다. 별도 `systemTest`는 실제 스택의 API·진단 이벤트·관측·장애를 JUnit으로 검증한다. `script/check.sh`는 Gradle 태스크를 호출하는 호환 진입점이다.

`-PcoldStart=true`는 Gateway를 재기동한 뒤 첫 보호 요청을 한 번만 확인한다. 존재하지 않는 테스트 사용자에 대한 User의 404를 기대해 Redis 연결 지연의 503과 구분한다. `-PredisFailure=true`는 Redis를 잠깐 pause하고 복구를 확인한다. 일반 시스템 검사에서는 두 옵션을 생략할 수 있다. 결과는 JUnit 리포트에 남으며 자세한 범위는 테스트 가이드를 따른다.

EC2 배포, 부하·운영 용량, 실제 PG, GitHub 원격 CI, IntelliJ UI 실행은 이 가이드의 검증 범위 밖이다.

Gateway 런타임에 spring-webmvc 의존성이 없는지는 Gradle dependencyInsight로 확인할 수 있다. 수정 판단은 [리뷰 수용 기록](../ai-judgments/junhyun/2026-10-26-two-agent-review-fixes.md), 재현 과정은 [TS-8~12](../troubleshooting.md)에 있다.

### JVM 보안과 연동 재검증

실행 중인 호스트 JVM은 먼저 종료한다. 아래 명령은 이 프로젝트의 앱만 정지하고 인프라를 유지한 상태에서 검사한다. `verifyJvm`은 앱 JAR를 준비하고 자신이 시작한 JVM을 성공·실패와 관계없이 종료한다. 실제 소켓 확인에는 `lsof`가 필요하다.

```bash
docker compose -f compose.yml -f compose.local.yml stop config-server eureka gateway user-service reservation-service payment-service mock-pg
docker compose -f compose.yml -f compose.local.yml -f compose.jvm.yml up -d --no-deps prometheus
./gradlew verifyJvm --no-daemon
docker compose -f compose.yml -f compose.local.yml up -d --build --wait --wait-timeout 180
```
