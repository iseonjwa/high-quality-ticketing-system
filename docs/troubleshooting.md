# 문제 해결 기록

막혔던 문제와 원인, 해결 방법을 남긴다. 작성 방법은 [`troubleshooting` 스킬](../.agents/skills/troubleshooting/SKILL.md)을 따른다. 항목은 `TS-{번호}`로 맨 아래에 추가한다.

### TS-1 JPA 감사 리스너의 Clock 주입 실패

- 서비스·작성: common / junhyun / 2026-10-26
- 문제: `./gradlew clean build --no-daemon`의 JPA 테스트 2건이 컨텍스트 생성 중 실패했다. 핵심 오류는 `No qualifying bean of type 'java.time.Clock' available`이다.
- 원인: Hibernate의 SpringBeanContainer가 리스너를 직접 생성할 때 생성자 인자를 Spring 빈으로 찾았다. 설정의 리스너 생성 메서드에 Clock 객체만 직접 넘겨서는 이 생성 경로를 충족하지 못했다.
- 해결: CommonJpaConfiguration에서 commonAuditingClock 빈을 제공하고 리스너를 import했다. 리스너 생성자에서 해당 시계 이름을 명시했다. 사용자 생성·수정·시스템 수정과 시스템 생성·명시적 논리 삭제의 JPA 테스트 2건이 통과했다.
- 배운 점: EntityManagerFactory를 직접 구성하면 리스너의 SpringBeanContainer 연결과 생성자 의존성을 함께 확인한다.

### TS-2 검증 테스트의 EL 런타임 의존성 누락

- 서비스·작성: common-web / junhyun / 2026-10-26
- 문제: `./gradlew build --continue --no-daemon`에서 `Could not find org.glassfish.expressly:expressly:.`로 테스트 런타임 의존성 해석이 실패했다.
- 원인: Spring Boot 4.1.2 BOM이 해당 expressly 좌표의 버전을 관리하지 않아 버전 없는 선언을 해석하지 못했다.
- 해결: 테스트 EL 런타임을 BOM이 관리하는 tomcat-embed-el로 바꿨다. MockMvc 검증 실패 테스트가 실행되어 400과 필드별 오류를 확인했다.

### TS-3 패키지로 제한한 예외 처리기가 404·405를 처리하지 못함

- 서비스·작성: common-web / junhyun / 2026-10-26
- 문제: POST로 GET 전용 API를 호출한 테스트에서 HTTP 405는 반환했지만 6키 응답 본문은 없었다.
- 원인: 메서드 불일치로 컨트롤러가 결정되기 전에 오류가 발생했다. basePackages로 제한한 RestControllerAdvice는 이 경우 적용할 컨트롤러 패키지가 없었다.
- 해결: CommonRoutingExceptionHandler가 매핑 전의 404·405를 처리하되 `/api/v1/` 경로만 공통 응답으로 변환하도록 했다. 나머지 예외는 기존 패키지 제한을 유지한다. 404·405·Allow 헤더와 외부 컨트롤러 제외 테스트가 통과했다.

### TS-4 test 프로필에서도 원격 Config import가 실행됨

- 서비스·작성: user-service / junhyun / 2026-10-26
- 문제: 인증 통합 테스트가 `Incorrect ConfigDataLocationResolver chosen`으로 컨텍스트 생성 전에 실패했다.
- 원인: 공통 application.yml의 필수 configserver import는 남아 있는데 테스트가 Config Client resolver만 비활성화했다. Standard resolver가 해당 주소를 파일로 해석했다.
- 해결: 원격 import를 `!test` 프로필 문서로 분리하고 기본 프로필은 local로 지정했다. test는 실제 PostgreSQL·Redis를 사용하되 원격 설정과 Eureka를 사용하지 않는다. 인증 통합 테스트가 통과했다.

### TS-5 Kafka named volume의 쓰기 권한으로 기동 실패

- 서비스·작성: infra / junhyun / 2026-10-26
- 문제: Kafka 4.1.1이 `AccessDeniedException: bootstrap.checkpoint.tmp`로 종료됐다.
- 원인: 새 named volume 최상위 소유자가 root여서 Kafka 런타임 사용자 UID 1000이 메타데이터를 쓸 수 없었다.
- 해결: kafka-init이 볼륨 최상위 소유권만 UID/GID 1000으로 맞추고, 성공 후 Kafka를 일반 사용자로 실행한다. 기존 데이터·볼륨은 삭제하지 않는다. 전체 기동과 실제 Kafka 송수신을 확인했다.

### TS-6 Boot 4에서 KafkaTemplate과 리스너 팩토리가 없음

- 서비스·작성: common-messaging / junhyun / 2026-10-26
- 문제: Reservation은 KafkaTemplate이 없고 Payment는 kafkaListenerContainerFactory가 없어 기동에 실패했다.
- 원인: spring-kafka 라이브러리만 추가해 Boot 4의 Kafka 자동 설정 모듈이 클래스패스에 없었다.
- 해결: common-messaging에서 spring-boot-starter-kafka를 사용했다. 앱 기동과 Reservation → Kafka → Payment 진단 이벤트 전달을 확인했다.

### TS-7 Redis 타임아웃 뒤 기존 토큰으로 복구를 판정해 검증 실패

- 서비스·작성: 인증 스모크 / junhyun / 2026-10-26
- 문제: Redis pause 중 로그아웃이 503을 반환한 뒤 unpause했을 때 기존 Access가 200으로 돌아오지 않아 복구 검증이 실패했다.
- 원인: 검증이 타임아웃 응답을 변경 전 상태가 유지된다는 뜻으로 해석했다. 클라이언트 명령 타임아웃은 Redis 서버의 미실행을 보장하지 않으므로 이 가정으로 복구를 판정할 수 없다. 당시 Redis 명령의 처리 순서는 미확인이다.
- 해결: 장애 중 성공을 반환하지 않는지와, 복구 후 새 로그인·새 Access 인증이 가능한지를 분리해 검증했다. 상태 불확실성은 ADR 0005와 워크스페이스 가이드에 명시했다.

### TS-8 JVM 모드의 포트가 외부 인터페이스에 열림

- 서비스·작성: workspace / junhyun / 2026-10-26
- 문제: Compose의 loopback 공개 제한이 JVM에는 적용되지 않아 사용자 헤더를 신뢰하는 서비스에 LAN에서 접근할 수 있는 구조였다.
- 원인: JVM 바인딩과 서비스 탐색 주소를 제한하지 않았다.
- 해결: local·Config Server 기본값과 runner의 업무·관리 주소를 loopback으로 지정했다. Compose에는 내부 주소를 명시했다. JVM 앱 7개의 리스너 14개, Eureka 앱 5개의 127.0.0.1 등록, HTTP와 Docker Prometheus 수집을 확인했다.

### TS-9 관리자 경로 변형이 권한 검사를 통과함

- 서비스·작성: gateway·common-web / junhyun / 2026-10-26
- 문제: USER의 정상 관리자 요청은 403이지만 관리자 루트·matrix parameter 변형은 필터를 통과했다. 관리자 데이터 접근 성공을 확인한 것은 아니다.
- 원인: 라우터는 인코딩과 경로 매개변수를 해석하는데 필터는 raw 접두어만 검사했다.
- 해결: Spring PathPattern으로 API 범위와 관리자 경로를 검사한다. 루트·matrix parameter·인코딩·컨텍스트 경로 테스트와 실제 HTTP에서 USER 차단을 확인했다.

### TS-10 Compose와 JVM의 dotenv 해석 불일치

- 서비스·작성: script / junhyun / 2026-10-26
- 문제: 따옴표 있는 비밀번호를 JVM 파서는 따옴표까지 포함해 전달했다.
- 원인: 단순 줄 분할이 dotenv 규칙을 해석하지 않았다.
- 해결: 컨테이너 생성 없이 Compose config로 해석한 결과를 공통 모듈에서 사용한다. canonical 출력의 달러 이스케이프는 한 번만 복원한다. 따옴표·주석·줄바꿈·달러 개수·상위 환경변수 우선순위 회귀 검사를 추가했다.

### TS-11 로그인에서 72바이트 뒤의 입력을 무시함

- 서비스·작성: user-service / junhyun / 2026-10-26
- 문제: 72바이트 비밀번호에 접미사를 붙여도 BCrypt가 일치로 처리했다.
- 원인: 가입만 UTF-8 바이트 제한을 검사했다.
- 해결: 가입·로그인의 바이트 검사를 공유하고 72바이트 초과는 400 VALIDATION_FAILED로 거절한다. ASCII·한글의 72·73바이트 경계를 통합 테스트와 실제 Gateway에서 확인했다.

### TS-12 같은 테스트 포트에 관리 주소를 별도로 지정해 실패

- 서비스·작성: user-service 테스트 / junhyun / 2026-10-26
- 문제: 관리 주소를 추가한 테스트가 Management-specific server address 오류로 기동하지 못했다.
- 원인: 테스트의 관리 엔드포인트는 업무 서버와 같은 임의 포트를 사용한다.
- 해결: 테스트는 server.address의 loopback을 공유한다. 포트가 분리된 실제 앱은 두 주소를 모두 제한한다. 전체 테스트를 다시 통과했다.

### TS-13 누락된 @RequestAttribute를 구분할 예외 클래스가 없음

- 서비스·작성: common-web / junhyun / 2026-10-27
- 문제: 사용자 헤더 없이 `@RequestAttribute`가 필요한 API를 호출하면 400이 나갔다. 401로 바꾸려고 `MissingRequestAttributeException`을 import했더니 `cannot find symbol`로 컴파일되지 않았다.
- 원인: Spring Framework 7.0.10에는 그런 클래스가 없다. 누락된 요청 속성은 하위 클래스 없이 `ServletRequestBindingException` 자체로 던져진다. 헤더·파라미터 누락 예외는 이 클래스의 하위 클래스다.
- 해결: `handleServletRequestBindingException`을 재정의해 `getClass() == ServletRequestBindingException.class`일 때만 401로 바꾸고, 나머지 하위 예외는 기존 처리를 유지했다. 속성이 없을 때 401, 있을 때 200인 MockMvc 테스트를 추가했다.
- 배운 점: 예외 타입을 기억에 의존해 쓰지 말고 사용하는 프레임워크 버전의 클래스 목록에서 확인한다.

### TS-14 Set.of().contains(null)이 NPE를 던짐

- 서비스·작성: common-web·common-auth / junhyun / 2026-10-27
- 문제: `X-User-Role` 헤더만 없는 요청의 필터 테스트가 `NullPointerException: Cannot invoke "Object.equals(Object)" because "o" is null`로 실패했다.
- 원인: 역할 검사를 `Set.of(...).contains(role)`로 했는데, 불변 컬렉션 `Set.of`는 `contains(null)`에서 NPE를 던진다. 같은 코드가 JWT 클레임 검증에도 있었고 그쪽은 광범위한 catch에 가려 우연히 거절로 처리되고 있었다.
- 해결: `Roles.isValid(role)`이 null을 먼저 거르도록 하고 두 곳이 모두 이 메서드를 쓰게 했다. 역할 헤더 누락·잘못된 값 6가지를 파라미터 테스트로 추가했다.
- 배운 점: 외부 입력을 불변 컬렉션에 바로 넣어 조회하지 않는다. 광범위한 catch가 버그를 가리는지 의심한다.

### TS-15 비밀번호 초기화 뒤 이전 로그인 요청이 Refresh를 다시 저장함

- 서비스·작성: user-service / junhyun / 2026-10-27
- 문제: BCrypt 비교 중 초기화·변경을 끝내도 진행 중이던 로그인이 이전 비밀번호 상태로 성공하고 Refresh를 재생성했다.
- 원인: 비밀번호 검증 시 읽은 상태를 발급 시점에 재확인하지 않았고 발급·폐기가 서로 직렬화되지 않았다.
- 해결: BCrypt 뒤 새 트랜잭션에서 사용자 행을 잠그고 최신 해시·잠금을 확인한다. 세션 발급·비밀번호 변경·초기화의 Redis 작업은 같은 사용자 행 잠금으로 조율한다. 실패 횟수 증가는 잠금 트랜잭션 밖에 유지한다. 상세 범위는 [ADR 0010](decisions/0010-auth-session-serialization.md)에 있다.
- 검증: 실제 PostgreSQL·Redis·HTTP에서 새 경합 2개가 수정 전 실패했고 수정 후 인증 통합 테스트 18개가 통과했다.

### TS-16 잠금·비밀번호 변경 필요 상태에서도 Refresh가 회전함

- 서비스·작성: user-service / junhyun / 2026-10-27
- 문제: 로그인은 계정 상태를 확인하지만 Refresh는 잠금·변경 필요 상태에서도 새 토큰을 발급했다.
- 원인: 재발급 경로에 현재 계정의 발급 가능 상태 검사가 없었다. 상태 검사만 먼저 추가하면 초기화로 이미 폐기된 토큰의 기존 401 응답도 403으로 바뀌었다.
- 해결: 활성 Refresh 세션을 먼저 확인한 뒤 잠금 423·변경 필요 403으로 거절한다. 정상 교체는 기존 Lua 비교·교체로 다시 확인한다. 거절 시 저장된 해시는 유지한다.
- 검증: 새 상태 조합 2개는 수정 전 실패했고, 수정 후 기존 초기화·폐기 응답과 동시 재사용을 포함한 인증 통합 테스트 20개가 통과했다.

### TS-17 Gateway 준비 완료 뒤 첫 인증 요청이 Redis 연결 때문에 503을 반환함

- 서비스·작성: gateway / junhyun / 2026-10-27
- 문제: 기동 직후 실제 Redis 연결이 준비되지 않아 첫 인증 조회가 100ms를 넘겼다는 관찰이 ADR 0005에 남아 있었다.
- 원인: readiness는 기본적으로 Redis 연결 준비를 확인하지 않고 첫 보호 요청이 연결 수립 비용을 부담했다.
- 해결: ApplicationRunner에서 PING·PONG을 확인한 뒤 준비 완료로 전환한다. 시도당 2초, 추가 2회 재시도로 기동 대기를 제한한다. 요청별 100ms·서킷·런타임 장애 정책은 유지한다.
- 검증: 예열 5개를 포함한 Gateway 테스트 42개 통과. 실제 컨테이너 재기동 뒤 첫 보호 요청이 Redis 503 없이 User의 예상 404로 도달했다. 당시 Python 스모크로 확인했고, 이후 이관한 현행 검사는 `./gradlew :system-tests:systemTest -PcoldStart=true --no-daemon`이다.

### TS-18 잘못된 인증 설정을 첫 요청에서야 발견함

- 서비스·작성: user-service / junhyun / 2026-10-27
- 문제: 음수 만료 시간을 넣어도 JwtTokens 생성은 성공하고 발급 때 `expiresAt must be after issuedAt` 예외가 발생했다. 로그인 실패 제한의 0·음수도 기동 때 거절하지 않았다.
- 원인: 키 길이·분리만 검사하고 정책 값의 양수 조건은 확인하지 않았다.
- 해결: JwtTokens와 AuthService 생성자에서 만료 초·실패 제한을 검증해 잘못된 설정이면 빈 생성을 실패시킨다. 새 설정 계층이나 라이브러리는 추가하지 않았다.
- 검증: 기동 컨텍스트 테스트 8개 중 잘못된 설정 6개가 수정 전 실패했고 수정 후 모두 통과했다. 기존 인증 통합 20개도 통과했다. 최종 전체 검사에서 JUnit 105개·Python 14개, 실제 스택 스모크에서 HTTP 계약 55건과 Redis 장애·복구가 통과했다.

### TS-19 시간 초과 명령의 JVM 종료 확인이 끝나기 전에 반환함

- 서비스·작성: workspace-tools / junhyun / 2026-10-27
- 문제: 시간 초과 뒤 자식 JVM이 남지 않는 회귀 테스트에서 종료 신호 직후 프로세스가 잠깐 살아 있다고 판정됐다.
- 원인: 강제 종료 요청만 보내고 OS의 종료·회수 완료를 기다리지 않았다.
- 해결: 외부 명령의 자식과 본체를 정리한 뒤 `waitFor`로 실제 종료를 확인한다. 소유한 앱 JVM도 정상 종료를 기다리고 제한 시간을 넘기면 강제 종료한다.
- 검증: 실패했던 실제 자식 JVM 시간 초과 테스트와 종료 대기 테스트를 포함해 도구 JUnit 18개, 기본 전체 124개가 통과했다. Java JVM 모드와 단일 앱 실행·중단 뒤 종료도 확인했다.

### TS-20 config-repo 설정 파일을 틀리게 바꿔도 테스트가 통과한다
- 서비스·작성: config-server / kangmin / 2026-10-27 (강민 초기 환경 브랜치 `ggangmy/chore/init-env`, 커밋 해시는 그 브랜치 기준)
- 문제: `config-repo/application-local.yml`의 Kafka 주소를 일부러 틀리게 바꿨는데 `./gradlew :config-server:test`가 성공(exit 0)했다. 테스트 결과에 실패가 없었다.
- 원인: 테스트는 실행 중에 `../config-repo/` 파일을 읽지만, Gradle은 그 폴더를 test 작업의 입력으로 알지 못한다. 소스·클래스패스가 그대로면 test 작업을 최신 상태(또는 빌드 캐시 적중)로 보고 실행하지 않는다.
- 해결: 서비스 컨벤션 플러그인에서 모든 Test 작업에 `inputs.dir(rootDir.resolve("config-repo"))`를 등록했다(`build-logic/.../iseonjwa.spring-boot-service.gradle.kts`, 커밋 `c9cf5bd`). 같은 변이로 다시 돌려 실패(exit 1)를 확인했다.
- 배운 점: 테스트가 소스 밖의 파일을 읽으면 Gradle 입력으로 등록해야 한다. 변이 테스트를 하지 않았으면 몰랐을 문제다.

### TS-21 Config 클라이언트를 끈 test 프로필에서 `configserver:` import 때문에 기동이 멈춘다
- 서비스·작성: eureka 외 모든 서비스 / kangmin / 2026-10-27 (강민 초기 환경 브랜치 `ggangmy/chore/init-env`, 커밋 해시는 그 브랜치 기준)
- 문제: `spring.cloud.config.enabled=false`인 test 프로필로 띄우면 `Unable to load config data from 'configserver:http://localhost:8888'`, `Incorrect ConfigDataLocationResolver chosen`으로 실패했다.
- 원인: Config 클라이언트를 끄면 `configserver:` 주소를 해석하는 리졸버도 빠진다. 그런데 `spring.config.import`는 남아 있어 일반 파일 경로로 해석하려다 실패한다.
- 해결: import를 `optional:configserver:...`로 바꿨다. 실제 기동에서는 `spring.cloud.config.fail-fast=true` 때문에 Config 서버가 없으면 여전히 멈추는 것을 확인했다(`ConfigClientFailFastException`, exit 1).
- 배운 점: `optional:`은 "없어도 된다"가 아니라 "리졸버가 없을 때 건너뛴다"에 가깝다. 필수 여부는 `fail-fast`가 정한다.
- 정정(2026-10-27): 이 해결은 틀렸다. 검증할 때 compose의 config-server가 떠 있어서 테스트가 Config 서버에 실제로 접속해 통과한 것으로 보인다. Config 서버를 끄면 테스트 7개가 실패했다. 실제 원인과 해결은 TS-24에 있다.

### TS-22 서비스 이미지를 동시에 빌드하면 Gradle 캐시 잠금에서 실패한다
- 서비스·작성: 전체(Dockerfile) / kangmin / 2026-10-27 (강민 초기 환경 브랜치 `ggangmy/chore/init-env`, 커밋 해시는 그 브랜치 기준)
- 문제: `docker compose up --build`에서 6개 이미지 빌드가 모두 `Timeout waiting to lock journal cache (/root/.gradle/caches/journal-1). It is currently in use by another process. Owner PID: 43 / Our PID: 43`으로 실패했다.
- 원인: 서비스마다 다른 컨테이너에서 같은 BuildKit 캐시 마운트(`/root/.gradle`)를 동시에 썼다. 컨테이너마다 Gradle의 PID가 같아(43) 잠금 주인이 누구인지 구분하지 못했다.
- 해결: 빌드 단계를 MODULE과 무관하게 모든 서비스 jar를 한 번에 만들도록 바꿨다. 여섯 이미지의 빌드 단계가 같아져 BuildKit이 한 번만 실행한다. 캐시 마운트에는 `sharing=locked`를 더했다(커밋 `ba27217`).
- 배운 점: 멀티모듈을 서비스별 이미지로 나눌 때는 빌드 단계를 공유하는 편이 빠르고 안전하다.

### TS-23 로컬에서 gateway가 8080 포트를 잡지 못한다
- 서비스·작성: gateway / kangmin / 2026-10-27 (강민 초기 환경 브랜치 `ggangmy/chore/init-env`, 커밋 해시는 그 브랜치 기준)
- 문제: `Bind for 0.0.0.0:8080 failed: port is already allocated`로 gateway 컨테이너만 만들어지고 시작하지 않았다.
- 원인: 같은 PC에서 다른 프로젝트의 컨테이너가 8080을 쓰고 있었다.
- 해결: compose의 gateway 포트를 `${GATEWAY_PORT:-8080}:8080`으로 바꿨다. 8080이 비어 있으면 그대로 쓰고, 충돌하면 `.env`에 `GATEWAY_PORT=18080`처럼 넣는다(커밋 `c184dce`).

### TS-24 Config 서버가 꺼져 있으면 test 프로필 테스트가 실패한다
- 서비스·작성: eureka, gateway, mock-pg, user·reservation·payment-service / kangmin / 2026-10-27 (강민 초기 환경 브랜치 `ggangmy/chore/init-env`, 커밋 해시는 그 브랜치 기준)
- 문제: Config 서버가 꺼진 상태에서 `./gradlew build`를 돌리면 테스트 11개 중 7개가 `ConfigClientFailFastException`(`localhost:8888` Connection refused)으로 실패했다. test 프로필 문서에 `spring.cloud.config.enabled: false`를 두었는데도 접속을 시도했다. compose 스택이 떠 있을 때는 통과해서 TS-21 검증 때 놓쳤다.
- 원인: `spring.config.import`를 기본 문서(프로필 조건 없음)에 두었다. 실험해 보면 이 import는 test 프로필 문서의 `enabled: false`가 적용되기 전에 처리된다. 같은 값을 `@SpringBootTest` 속성으로 넘기거나 import를 test가 아닐 때만 하도록 옮기면 통과한다. Boot 소스로 처리 순서를 확인하지는 않았다.
- 해결: 6개 서비스의 `application.yml`에서 import를 `spring.config.activate.on-profile: "!test"` 문서로 옮기고 `optional:`을 뺐다. test 프로필에서는 import 자체가 없다. 확인한 것: Config 서버 없이 테스트 11개 통과, user-service를 이전 형태로 되돌리면 실패(변이 테스트), Config 서버 없이 jar를 실행하면 `ConfigClientFailFastException`으로 멈춤(exit 1), compose 전체 기동 시 6개 컨테이너가 `Located environment`로 설정을 받고 config-repo의 포트로 뜸.
- 배운 점: 외부 서버에 기대는 테스트는 그 서버가 꺼진 상태에서도 돌려 봐야 한다. 로컬에 인프라가 떠 있으면 테스트가 우연히 통과한다. 빌드를 돌리는 CI가 있으면 이런 문제를 PR에서 잡는다.

### TS-25 멀티모듈 전환 후 루트 `./gradlew build` 실패 (common bootJar, test fixtures, Eureka 의존성)
- 서비스·작성: 공통(common·Gateway·Eureka·도메인 서비스) / kwonwoo / 2026-10-27 (권우 초기 환경 브랜치 `kwonwoo/feat/common`)
- 문제: 단일 모듈을 `common`, `eureka-registry`, `gateway-edge`, `*-service`로 나눈 뒤 빌드가 실패했다. 앞 오류를 고치면 다음 오류가 드러나는 순서였다.
  1. `Execution failed for task ':common:bootJar'` / `Main class name has not been configured`
  2. `Unable to find a variant of 'project ':common'' with the requested capability: feature 'test-fixtures'`
  3. `org.springframework.boot:spring-cloud-starter-netflix-eureka-client FAILED` (user·reservation·payment 서비스)
- 원인:
  1. 라이브러리인 `common`에 `org.springframework.boot` 플러그인이 적용돼 실행 jar(`bootJar`)를 만들려 했고, `main` 클래스가 없었다.
  2. 루트가 서비스에 `testFixtures(project(':common'))`를 걸었지만 `common`에 `java-test-fixtures` 플러그인이 없었다.
  3. Eureka client의 group을 `org.springframework.boot`로 적었다. 올바른 group은 `org.springframework.cloud`다.
- 해결:
  - `common/build.gradle`을 `java-library` + `java-test-fixtures`로 바꾸고 Boot 플러그인을 뺐다. `@SpringBootTest`만 있던 `CommonApplicationTests`는 설정 클래스가 없어 실패하므로 지웠다.
  - 공통 의존성을 루트 `build.gradle`로 모았다. 모든 모듈은 Java 21·BOM·Lombok·`spring-boot-starter-test`, 실행 모듈은 Boot 플러그인·Actuator·Prometheus, 서비스는 common·Eureka client·webmvc·JPA·validation·PostgreSQL이다.
  - 모듈 안에 남은 Initializr 단독 빌드 파일(`settings.gradle`, `gradlew`, `gradle/wrapper`, `.gitattributes`)을 지웠다.
  - 테스트에서 Gateway·서비스는 `eureka.client.enabled=false`로 Eureka 접속 시도를 끈다. `eureka-registry`에 같은 값을 주면 `No qualifying bean of type 'com.netflix.appinfo.ApplicationInfoManager'`로 실패하므로 제외했다.
  - 루트 `ext`의 `springCloudVersion`을 서브프로젝트가 암묵적으로 찾는 방식은 Gradle 9.8에서 deprecation 경고(Gradle 10에서 오류)가 나서 스크립트 변수로 바꿨다.
  - 결과: `./gradlew clean build --warning-mode all` 성공, 경고 0, 테스트 5개 통과. Eureka와 Gateway jar를 띄워 Gateway 등록과 경로별 `lb://` 서비스 이름 해석을 확인했다.
- AI 활용: Claude Code가 `--continue`와 모듈별 `dependencies` 해석으로 숨은 오류까지 찾고 수정안을 냈다. 판단 기록은 [ai-judgments/kwonwoo/2026-10-27-multimodule-build-fix.md](ai-judgments/kwonwoo/2026-10-27-multimodule-build-fix.md).
- 배운 점: 멀티모듈에서 첫 실패가 뒤 오류를 가리므로 `./gradlew build --continue`와 `:<모듈>:dependencies`로 한 번에 드러낸다. 라이브러리 모듈에는 Boot 플러그인을 적용하지 않는다.

### TS-26 IntelliJ Gradle 동기화 실패: `Task 'prepareKotlinBuildScriptModel' not found in project ':common'`
- 서비스·작성: 공통(IntelliJ 설정) / kwonwoo / 2026-10-27 (권우 초기 환경 브랜치 `kwonwoo/feat/common`)
- 문제: IntelliJ에서 Gradle을 다시 불러오면 모든 모듈이 `Task 'prepareKotlinBuildScriptModel' not found in project ':common'.` / `BUILD FAILED`로 실패했다. 터미널 `./gradlew clean build`는 성공했다.
- 원인: 모듈을 Spring Initializr로 따로 만들면서 IntelliJ의 `.idea/gradle.xml`에 루트 외에 모듈 6개가 각각 독립 Gradle 프로젝트로 연결돼 있었다. 모듈 안의 `settings.gradle`을 지운 뒤에는 모듈 폴더에서 실행한 Gradle이 루트 빌드의 하위 프로젝트(`:common`)로 동작한다. IDE 연동용 `prepareKotlinBuildScriptModel` 작업은 루트 프로젝트에만 있어서 하위 프로젝트 기준 요청이 실패한다. `./gradlew -p common prepareKotlinBuildScriptModel`로 같은 오류를 재현했고, 루트에서 실행하면 성공했다.
- 해결: Gradle 창에서 모듈 6개의 연결을 Unlink Gradle Project로 해제하고 루트만 남긴다(`.idea/gradle.xml`의 `GradleProjectSettings`가 루트 하나). 이어서 Reload All Gradle Projects를 실행한다. `.idea/`는 커밋 대상이 아니라 팀원마다 각자 한 번 해야 한다.
- 배운 점: 멀티모듈에서는 루트만 Gradle 프로젝트로 연결한다. Initializr로 모듈을 추가하면 모듈 안의 `settings.gradle`·`gradlew`와 IDE의 별도 연결이 함께 생기므로 둘 다 정리한다.

### TS-27 Vite 템플릿의 TypeScript 7과 typescript-eslint peer 충돌로 `npm install` 실패
- 서비스·작성: frontend / junhyun / 2026-11-02
- 문제: `create-vite` 9.3.3의 `react-ts --eslint` 템플릿으로 만든 직후 `npm install`이 `ERESOLVE could not resolve`로 실패했다. 추가 패키지를 설치하기 전, 템플릿 그대로의 상태였다.
- 원인: 템플릿이 `typescript ~7.0.2`를 넣었지만 `typescript-eslint` 8.72.1의 peer 범위가 `typescript >=4.8.4 <6.1.0`이었다. `npm view typescript-eslint@canary peerDependencies`로 canary도 같은 범위임을 확인했다. TypeScript 7은 Go로 다시 쓴 컴파일러라 typescript-eslint가 아직 따라오지 못했다.
- 해결: `--legacy-peer-deps`로 무시하지 않고 TypeScript를 6.x 최신인 6.0.3으로 고정했다. `npm install`, `npm run lint`, `npm run build`(`tsc -b`), `npm test`가 통과했다. 버전과 올릴 조건은 ADR 0040 5절에 적었다.
- 배운 점: 스캐폴딩 템플릿의 기본 버전도 서로 맞지 않을 수 있다. peer 충돌은 무시 옵션보다 맞는 조합을 찾는 쪽이 안전하다. 린터처럼 컴파일러 API에 기대는 도구는 컴파일러 메이저 버전을 늦게 따라온다.

### TS-28 마이그레이션 파일을 고친 뒤 로컬 테스트가 `Migration checksum mismatch`로 무더기 실패
- 서비스·작성: reservation / kangmin / 2026-11-02
- 문제: 아직 머지되지 않은 Flyway 마이그레이션(`V20261102_1__lottery_tables.sql`)의 주석 한 줄을 고친 뒤 `./gradlew :reservation-service:test`에서 137개 중 73개가 실패했다. 스프링 컨텍스트를 띄우는 테스트가 모두 `Validate failed: Migrations have failed validation` / `Migration checksum mismatch for migration version 20261102.1`로 죽었다. 같은 이유로 마이그레이션 CHECK 제약을 일부러 틀리게 바꾸는 변이 테스트도 관계없는 테스트까지 실패해 결론을 내지 못했다.
- 원인: `prepareTestInfra`가 `docker compose -f compose.test.yml up -d --wait`만 실행한다. 컨테이너가 이미 떠 있으면 다시 만들지 않으므로, tmpfs DB라도 컨테이너가 살아 있는 동안 이전 실행에서 적용한 마이그레이션과 checksum이 남는다. 파일 내용이 바뀌면 Flyway가 기록된 checksum과 달라 검증에서 막는다. CI는 매번 새 컨테이너로 시작하므로 이 문제가 없다.
- 해결: 로컬에서 `docker compose -f compose.test.yml up -d --force-recreate --wait postgres-reservation`으로 테스트 DB 컨테이너만 새로 띄웠다. 다시 실행해 137개가 모두 통과했고, `./gradlew verifyWorkspace`도 통과했다. 이미 머지된 마이그레이션은 고치지 않고 새 버전 파일을 더한다(다른 사람의 DB에 이미 적용됐기 때문이다).
- 배운 점: 머지 전 마이그레이션을 고쳤거나 마이그레이션 변이 테스트를 할 때는 테스트 DB 컨테이너를 새로 띄운다. `build.gradle:42`의 `up`만 하는 구조는 인수인계 메모에도 "의도인지 확인 못 함"으로 남아 있다. `--force-recreate`를 기본으로 할지는 실행 시간과 함께 팀이 정한다.

### TS-29 compose로 다시 띄운 서비스가 `Failed to configure a DataSource`로 기동 실패(config-server가 빈 설정을 줌)
- 서비스·작성: infra(config-server) / junhyun / 2026-11-02
- 문제: payment-service와 mock-pg 이미지를 새로 빌드해 `docker compose up -d --no-deps payment-service mock-pg`로 띄우자 둘 다 바로 종료됐다. payment-service는 `Failed to configure a DataSource: 'url' attribute is not specified`, mock-pg는 `Management-specific server address cannot be configured as the management server is not listening on a separate port`였다. 두 오류 모두 config-server에서 받아야 할 설정(`config-repo/*.yml`)이 없을 때 나는 오류다.
- 원인: config-server는 `config-repo`를 바인드 마운트로 읽는데, 컨테이너 안의 `/config-repo`가 비어 있었다(`ls`가 `total 0`, `/payment-service/local` 응답의 `propertySources: []`). 컨테이너는 10/26 22:46에 만들어졌고 호스트의 `config-repo` 디렉터리는 10/27 19:22에 새로 만들어졌다(`stat -f %SB config-repo`). 바인드 마운트는 컨테이너를 만들 때의 디렉터리를 가리키므로, 그 뒤 호스트에서 디렉터리가 지워졌다 다시 생기면 컨테이너는 빈 디렉터리를 본다. 이미 떠 있던 서비스는 기동할 때 설정을 받아 두어서 드러나지 않았고, 새로 띄운 서비스부터 실패했다.
- 해결: `docker compose up -d --no-deps --force-recreate config-server`로 config-server 컨테이너만 다시 만들었다(이미지는 그대로). `/config-repo`에 파일 11개가 보이고 `payment-service.yml`·`application.yml`·`application-local.yml`을 돌려주는 것을 확인한 뒤 두 서비스를 다시 띄우자 정상 기동했다.
- 배운 점: 설정 오류처럼 보이는 기동 실패가 여러 서비스에서 함께 나면 먼저 `docker compose exec config-server curl -s localhost:8888/<서비스>/local`로 config-server가 실제로 무엇을 주는지 본다. 빈 `propertySources`면 마운트 문제다.

### TS-30 squash 머지 뒤 같은 브랜치에 push한 커밋이 dev에 들어가지 않고 사라짐
- 서비스·작성: docs / kangmin / 2026-11-02
- 문제: PR #224(ISE-91)를 올린 뒤 같은 브랜치에 보고서 보강 커밋(`52107e6`)을 push했는데, 나중에 dev의 보고서에 그 절(8-1)이 없었다. 이슈는 Done, PR은 머지 상태라 빠진 것을 바로 알아채지 못했다.
- 원인: 보강 커밋을 push하기 직전에 PR이 squash 머지됐다. squash 머지는 그 시점의 브랜치 내용을 커밋 하나로 dev에 넣고 PR을 닫는다. 머지 뒤 같은 브랜치에 push한 커밋은 닫힌 PR에만 붙고 dev로 가는 경로가 없다. 로컬 브랜치를 지운 뒤라 커밋은 reflog에만 남아 있었다.
- 해결: `git show --stat 52107e6`으로 커밋이 남아 있는지 확인하고, 다음 작업 브랜치(ISE-89)에서 `git cherry-pick 52107e6`으로 되살렸다. 보고서에 8-1절이 다시 들어간 것을 `grep`으로 확인했다.
- 배운 점: PR에 커밋을 더 올리기 전에 `gh pr view <번호> --json state`로 아직 OPEN인지 본다. 이 확인을 `gh pr view … && git push`처럼 이어 쓰면 MERGED여도 명령이 성공(종료 코드 0)해 push가 그대로 나간다. 같은 날 한 번 더 겪었다(보고서 커밋, `2f09414`). `[ "$(gh pr view <번호> --json state -q .state)" = OPEN ] && git push`처럼 값을 비교한다. 머지된 뒤라면 새 브랜치(또는 다음 PR)로 올린다. 머지된 브랜치를 지우기 전에 `git log origin/dev..<브랜치>`로 dev에 없는 커밋이 남았는지 확인한다.

### TS-31 글자 충돌 없는 두 PR이 같은 Flyway 버전을 써서 머지 뒤 dev의 결제 서비스가 뜨지 않음
- 서비스·작성: payment / junhyun / 2026-11-03
- 문제: PR #229(ISE-145)를 머지하자 dev CI `build`가 머지 커밋 `54f399c`에서 실패했다. 결제 서비스 컨텍스트를 띄우는 테스트가 모두 `FlywayException: Found more than one migration with version 5`로 죽었다(payment-service 435건 중 268건 실패).
- 원인: 전날 밤 머지된 PR #236(ISE-96)의 `V5__billing_keys.sql`과 #229의 `V5__outbox_failed_key_index.sql`이 같은 버전 5였다. 파일 이름이 달라 GitHub는 충돌로 보지 않았다. dev 보호 규칙의 필수 검사(`build`, `records-check`)가 "최신 dev 기준"(strict)이 아니어서, #229의 초록 CI는 #236이 들어가기 전 dev 기준이었다.
- 해결: ISE-155(PR #248)로 나중에 들어온 Outbox 인덱스를 `V6__outbox_failed_key_index.sql`로 옮겼다. 먼저 들어온 빌링키 마이그레이션은 팀원 로컬 DB에 이미 적용됐을 수 있어 두었다. 로컬에서는 옛 `V5` 이력이 남은 테스트 DB 때문에 `Validate failed`가 이어 나서 TS-28처럼 `docker compose -f compose.test.yml up -d --force-recreate --wait postgres-payment`로 다시 만들었다. #248 머지 뒤 dev CI가 통과했다.
- AI 활용: 머지는 사용자가 AI에게 맡긴 작업이었다. AI가 다음 PR(#225)의 충돌을 풀다가 테스트 실패로 발견하고 바로 수정 이슈와 PR을 냈다.
- 배운 점: PR이 머지 가능(CLEAN)으로 보여도 CI가 최신 dev 기준이라는 뜻은 아니다. 마이그레이션을 더하는 PR은 머지 직전에 `git fetch && ls`로 dev의 마이그레이션 번호와 겹치지 않는지 보고, dev를 브랜치에 합쳐 테스트한 뒤 머지한다. 필수 검사를 strict로 켤지는 머지 대기가 늘어나는 비용과 함께 팀이 정한다.

### TS-32 로컬 테스트 환불의 NEEDS_MANUAL_CHECK 알림이 슬랙에 한 시간마다 반복되는데 ops 명령으로 닫히지 않음
- 서비스·작성: payment / junhyun / 2026-11-04
- 문제: 팀 슬랙 `#이선좌-성능-모니터링`에 "확인이 필요한 환불이 1건 있습니다(NEEDS_MANUAL_CHECK)"가 한 시간마다 다시 왔다. 알림은 `RefundNeedsManualCheck`이고 env는 로컬이다. 즉시 확인 알림이라 풀릴 때까지 1시간마다 반복된다.
- 원인: 11/2 로컬 테스트에서 만든 환불 1번이다. 그 뒤 Mock PG가 재시작되며 메모리 원장이 비어, PG에 원래 주문과 환불 기록이 없다.
  - `ops inspect --with-pg` 결과는 결제 "PG에 주문 없음", 환불 "PG에 환불 없음"이었다.
  - `refund resolve`는 PG `NotFound`를 거부한다. `--close-failed`도 `NotFound`에는 쓸 수 없다.
  - `refund retry`는 Mock PG가 다시 거절해 NMC로 돌아온다(11/3에 한 번 시도).
  - 대응 문서에 이 상태를 닫는 명령이 없다.
- 해결:
  1. 처음에는 데이터를 두고 로컬 Alertmanager에 무음을 걸었다(`env="local"`, 환불 알림 두 개).
  2. 사용자가 "로컬 테스트 데이터니까 지금 한 번만 데이터를 정상화"하라고 해서, `ops refund resolve --close-failed`가 하는 전이를 SQL로 똑같이 했다. 한 문장(CTE)이라 둘 다 되거나 둘 다 안 된다.
     - 환불 1번: `NEEDS_MANUAL_CHECK` → `FAILED`, `next_check_at = NULL`.
     - 상태 이력: 한 줄. 사유는 SQL로 닫았다는 것이 드러나게 `LOCAL_TEST_DATA_SQL_CLOSE`, actor는 `OPS:junhyun`.
     - 이벤트는 내지 않았고 결제 상태(`APPROVED`)는 그대로 뒀다. close-failed와 같다.
  3. `refund_manual_check_count`가 0이 되고 Prometheus·Alertmanager에서 환불 알림이 사라진 것을 확인한 뒤 무음을 지웠다.
- AI 활용: AI가 `inspect`로 원인을 찾고 처음에는 무음을 걸었다. 사용자가 로컬 데이터 정상화를 골랐고, AI가 운영 명령의 전이 코드(`RefundSettlementService.closeAsFailed`)를 읽고 같은 변경을 SQL로 했다.
- 배운 점:
  - Mock PG는 원장을 메모리에 두므로, 재시작 전에 만든 로컬 결제·환불은 PG 근거를 잃는다.
  - 운영 서버에서는 상태를 바꾸는 SQL을 쓰지 않고(`manual-recovery.md` 1절) PG 콘솔 확인과 8절 예외 절차를 따른다.
  - 로컬 테스트 데이터를 SQL로 고칠 때도 운영 명령과 같은 전이와 이력을 남기고, 사유로 SQL로 고친 것임을 드러낸다.

### TS-33 새 EC2에서 GitHub·Docker 저장소로 나가는 연결이 모두 시간 초과(들어오는 SSH는 됨)
- 서비스·작성: infra / junhyun / 2026-11-04
- 문제: 배포 EC2에서 `git clone https://github.com/...`이 `Failed to connect to github.com port 443 after 133577 ms`로 실패했다. `curl`로 github.com, ghcr.io, download.docker.com, archive.ubuntu.com, 1.1.1.1 모두 응답이 없었다. DNS 조회와 들어오는 SSH는 됐다.
- 원인: 직접 만든 보안 그룹 두 개(`iseonjwa-deploy-sg`, `iseonjwa-jenkins-sg`)에 아웃바운드 규칙이 없었다. 보안 그룹은 상태 저장이라 들어온 SSH의 응답은 나가지만, 서버가 먼저 여는 연결은 아웃바운드 규칙이 있어야 나간다.
- 해결: 두 보안 그룹에 아웃바운드 "모든 트래픽, 0.0.0.0/0"을 더했다. 바로 github.com 200, download.docker.com 200을 확인했다.
- 배운 점: 보안 그룹을 만들 때 아웃바운드 기본 규칙이 그대로 있는지 확인한다. "들어오는 SSH는 되는데 나가는 연결만 안 된다"는 증상이면 라우팅(인터넷 게이트웨이)보다 보안 그룹 아웃바운드를 먼저 본다.

### TS-34 첫 배포의 스모크 테스트가 공연 목록 503으로 실패(컨테이너는 모두 healthy)
- 서비스·작성: infra / junhyun / 2026-11-04
- 문제: `script/deploy/deploy.sh`의 `up --wait`는 통과했는데 바로 이어진 스모크에서 `/api/v1/concerts`가 503이었다. Gateway 로그는 `No servers available for service: reservation-service`였다.
- 원인: reservation-service가 기동해 Eureka에 등록한 뒤에도 Gateway가 레지스트리를 다시 받아 오기까지 약 35초가 걸렸다(`prod` 프로필은 Eureka 기본 갱신 주기). healthcheck는 각 컨테이너가 떴는지만 보므로 Gateway의 서비스 발견을 기다리지 않는다.
- 해결: `smoke.sh`가 항목마다 기대한 상태가 나올 때까지 5초 간격으로 최대 120초(`SMOKE_WAIT_SECONDS`) 다시 확인하게 고쳤다. 고치기 전에는 다음 배포 때 멀쩡한 새 버전이 스모크 실패로 되돌려질 수 있었다. 첫 배포는 다시 돌린 스모크가 통과해 현재 배포로 기록했다.
- 배운 점: 서비스 발견(Eureka)을 거치는 구조에서는 "컨테이너 healthy"와 "Gateway가 찾을 수 있음" 사이에 시간이 있다. 배포 확인은 실제 진입점으로, 기다리는 시간을 두고 한다.

### TS-35 프론트 CI에서 예매 결과 화면 테스트가 가끔 "조회 0건이어야 하는데 1건"으로 실패
- 서비스·작성: frontend / junhyun / 2026-11-05
- 문제
  - PR #344의 frontend CI에서 `ReservationPage.test.tsx`의 "결제 화면이 넣은 확정 예매가 캐시에 있으면 조회하지 않고 확정을 보인다"가 `expected [ …(1) ] to have a length of +0 but got 1`로 실패했다.
  - 같은 프론트 코드가 6분 전 CI와 로컬에서는 통과했다. 프론트 코드가 바뀌지 않은 머지 커밋에서만 실패했다.
- 원인
  - 바로 앞 테스트(헤더 예매 단계 "완료")가 예매 조회가 끝나기 전에 끝났다. 단계 목록은 조회 전에 그려지기 때문이다.
  - 그 조회 요청이 정리(cleanup) 뒤에 시작되어 다음 테스트가 붙인 요청 기록(`recordRequests`)에 잡혔다.
  - 목 ID 카운터(`mockDb.nextId`)는 테스트마다 1000에서 다시 시작한다. 그래서 두 테스트의 예매 경로(`/api/v1/reservations/1001`)가 같았다.
  - CI처럼 느린 환경에서만 요청 시작이 다음 테스트로 밀려 드러났다.
- 해결: `frontend/src/pages/reservations/ReservationPage.test.tsx`(PR #344, 커밋 `ed4cf7e`)
  - 앞 테스트는 조회 결과(확정 제목)까지 기다린다.
  - 캐시 확정 테스트는 카운터와 겹치지 않는 예매 번호(99001)를 쓴다.
  - 그 파일을 `--maxWorkers=2`로 2개 × 3번, 전체 1158개를 `--maxWorkers=4`로 돌려 통과했다.
- 배운 점
  - "요청이 0건"을 확인하는 테스트는 앞 테스트의 남은 요청에 약하다.
  - 테스트는 자기가 시작한 요청을 끝까지 기다린 뒤 끝낸다.
  - 요청 수를 세는 테스트는 다른 테스트와 겹치지 않는 ID를 쓴다.

### TS-36 Windows에서 요청 로그 테스트 8개만 실패(같은 커밋의 CI는 성공)
- 서비스·작성: common·gateway / kangmin / 2026-11-05
- 문제: `./gradlew test`에서 `RequestLogFilterTest` 3개와 `RequestLogWebFilterTest` 5개가 실패했다. 출력의 `요청`이 `??`로 찍혀 `CapturedOutput` 비교가 어긋났다. 같은 커밋의 Linux CI `build`는 성공했다.
- 원인: Windows 기본 인코딩이 `cp1252`인 PC에서는 Gradle 테스트 JVM의 `file.encoding`도 그 값이 되어 한글 로그가 깨진다. `JAVA_TOOL_OPTIONS`의 `stdout.encoding=UTF-8`로는 해결되지 않았다.
- 해결: 루트 `build.gradle`의 `tasks.withType(Test)`에 `defaultCharacterEncoding = 'UTF-8'`을 지정했다. 옵션 없이 `./gradlew test --rerun-tasks`를 다시 돌려 1,563개가 모두 통과했다.
- 배운 점: 한글을 비교하는 출력 캡처 테스트는 실행 환경의 인코딩에 영향을 받는다. 코드 결함으로 판단하기 전에 CI 결과와 실제 출력을 비교한다.

### TS-37 작업을 멈춰도 PaymentJobStalled가 울리지 않음(규칙 단위 테스트는 통과)
- 서비스·작성: payment·infra / kangmin / 2026-11-05
- 문제
  - `payment_job_last_success_timestamp{application="payment-service",job="gauge-refresh"}` 조회가 0건이었고, `exported_job="gauge-refresh"`는 1건이었다.
  - `PaymentJobStalled`는 시계열을 찾지 못해 작업이 멈춰도 울릴 수 없었다. 작업 실패 패널의 `sum by (job)`도 모든 작업을 `spring` 하나로 합쳤다.
- 원인
  - Micrometer 태그 `job`이 Prometheus 수집 작업 라벨과 겹쳤다. `honor_labels`가 없으면 Prometheus는 대상이 보낸 값을 `exported_job`으로 옮기고 `job`에 수집 작업 이름(`spring`)을 넣는다.
  - promtool 테스트는 입력 시계열에 `job`을 직접 넣어 이 변환을 건너뛰었다. 그래서 테스트가 통과했다.
- 해결: ISE-195
  - `PaymentMetrics`의 태그를 `task`로 바꾸고 규칙, 대시보드, 슬랙 템플릿, 문서를 맞췄다.
  - promtool 테스트 입력을 실제 수집 형태(`instance`, `job="spring"`, `task`)로 바꿨다.
  - `PrometheusNamesTest`에 `job`·`instance` 태그를 막는 검사를 더했다.
  - 임시 DB를 멈춰 `gauge-refresh` 72초, `recheck`·`refund-worker` 145초에 firing이 되는 것을 확인했다.
- 배운 점
  - 지표 태그에 `job`·`instance`를 쓰지 않는다.
  - 규칙 단위 테스트의 입력은 실제 Prometheus 조회 결과를 옮겨 와 만든다.
  - 경보는 실제로 장애를 내서 울리는지 본다.

### TS-38 local 부하 측정에서 예매 조회 API가 모두 404(서비스 로그는 `GET /** 404`)
- 서비스·작성: reservation / kwonwoo / 2026-11-05
- 문제: k6 R1(선점 → 결제 → 예매 조회)에서 `GET /api/v1/reservations/{id}` 2,120건이 모두 404였다. DB에는 그 예매가 CONFIRMED로, 같은 사용자 ID로 있었다. reservation-service 요청 로그가 경로 대신 `요청 GET /** 404`였다(맞는 핸들러가 없다는 뜻).
- 원인: local에서 돌던 reservation-service 이미지가 2026-11-04 10:20 빌드였다. 예매 조회 API(ISE-171, #318)는 그 뒤에 들어와 이미지에 없었다. `docker compose up`은 이미지가 있으면 다시 빌드하지 않는다.
- 해결: `docker compose ... build reservation-service`로 현재 브랜치(`534187e`)에서 다시 빌드하고 띄웠다. R1 212건이 모두 확정됐다.
- 배운 점: 측정 전에 `docker image inspect`로 이미지 빌드 시각을 보고, 측정 기록표의 커밋 해시는 이미지를 만든 커밋으로 적는다. 서비스 로그의 URI 템플릿이 `/**`이면 경로가 아니라 핸들러가 없는 것이다. 2026-11-05 이전 local K1 측정은 11-04 이미지로 잰 것이다.

### TS-39 만료 스케줄러를 다시 켰는데 새로 만든 선점이 만료되지 않는다
- 서비스·작성: reservation / kwonwoo / 2026-11-05
- 문제: k6 X1에서 선점 시간 1분짜리 선점 500건이 만료 시각을 90초 넘게 지나도 HELD였다. 스케줄러는 켜져 있었고 로그에 `선점 만료 100건 처리 (대상 100건)`이 5~6초마다 찍혔다.
- 원인: 배포 메모리 측정 덮어쓰기(`reservation-service/k6/compose.deploy-memory.yml`)가 측정 중 만료가 끼지 않게 `SEATHOLD_EXPIRY_ENABLED=false`로 둔다. 그 상태로 잰 측정들의 선점 7,222건이 만료되지 않고 남아 있었다. 스케줄러는 오래된 것부터 100건씩 집으므로 묵은 선점을 먼저 처리했다.
- 해결: 묵은 선점이 0이 될 때까지 기다린 뒤 다시 쟀다. 500건이 만료 시각부터 약 33초 안에 모두 EXPIRED가 됐다. X1 전에 `select count(*) from reservations where status='HELD' and hold_expires_at < now()`가 0인지 보는 절차를 `loadtest/k6/README.md`에 적었다.
- 배운 점: 기능을 끈 채 측정하면 그 기능이 처리할 데이터가 쌓인다. 묵은 선점을 처리하면서 실제 만료 속도(초당 약 15.6건, 한 주기 약 6.4초)도 쟀다. 계산값(초당 20건)은 주기 간격만 보고 처리 시간을 빠뜨렸다.

### TS-40 mock-pg를 다시 띄운 직후 결제 부하에서 `PG_UNAVAILABLE`·504가 섞인다
- 서비스·작성: payment / kwonwoo / 2026-11-05
- 문제: 장애 주입 API를 열려고 mock-pg를 `local,loadtest` 프로필로 다시 띄운 직후 k6 PAY1을 돌리자 초당 5~10건인데도 503 `PG_UNAVAILABLE` 34건, 504 11건이 났다(PG 동시 한도 10에 한참 못 미치는 속도). 결제 서비스 로그는 `ResourceAccessException: I/O error on POST request for "http://mock-pg:8090/mock-pg/payments": closed`, `UnresolvedAddressException`이었다.
- 원인: mock-pg가 내려가 있던 동안과 직후에 결제 서비스의 PG 호출이 연결 끊김·주소 해석 실패로 실패했다. 이 실패가 `pg-approve` 차단기의 실패율로 세져 차단기가 열렸고, 열린 동안 승인 요청이 바로 503이 됐다.
- 해결: mock-pg가 안정된 뒤 다시 쟀다. 같은 조건에서 승인 208건, `PG_UNAVAILABLE` 4건이었다. 남은 4건은 발생기 멈춤(TS-41) 구간에 요청이 몰린 때와 겹친다.
- 배운 점: 의존 서비스를 다시 띄운 직후에는 차단기 상태가 남는다. 측정 전에 30초(차단기 OPEN 10초 + 여유) 넘게 기다리거나 차단기 상태 지표를 확인한다.

### TS-41 local Docker Desktop의 k6 컨테이너가 약 32초마다 3초씩 멈춘다
- 서비스·작성: loadtest / kwonwoo / 2026-11-05
- 문제: k6 Q2(서버가 준 간격으로 폴링)의 `poll_lateness`(예정 시각보다 늦게 부른 정도)가 약 30초 간격으로 최대 3초까지 튀었다. VU를 10개에서 20개로 늘려도 같은 모양이었다. 서버 응답 시간(p95 10ms)과 연결 시간은 정상이었다.
- 원인: 미확인. 서버 없이 `sleep(0.1)`만 반복하는 k6 스크립트에서도 같은 멈춤(3,028~3,032ms, 약 32초 간격)이 나와 서버나 스크립트가 아니라 Docker Desktop(WSL2) 쪽이다. 서버 컨테이너도 같이 멈추는지는 확인하지 못했다.
- 해결: 우회. local 결과에서 3초 근처의 최대값과 그 주기의 몰림은 발생기 탓일 수 있다고 README에 적었고, 본 측정은 발생기 EC2(ADR 0046)에서 한다. Q2에 발생기 지연 지표(`poll_lateness`)를 두어 같은 문제를 바로 알 수 있게 했다.
- 배운 점: 부하 생성기의 지연도 지표로 남겨야 서버 문제와 가를 수 있다. 열린 모델의 `dropped_iterations`가 0이어도 발생기가 멈췄을 수 있다.

### TS-42 Gateway가 수 ms 만에 돌려주는 503을 Eureka 이탈로 잘못 읽었다
- 서비스·작성: gateway / kwonwoo / 2026-11-05
- 문제: K1 부하 측정(P0'·P0'')과 K1-H에서 Gateway가 2~5ms 만에 503을 수천 건 돌려줬다. 처음에는 서비스가 Eureka에서 빠져 생긴 것으로 기록했다.
- 원인: 그 시간대 Gateway 로그에 `No servers available for service`가 없었다. 대신 Prometheus `resilience4j_circuitbreaker_state{name="auth-redis",state="open"}`이 503 구간과 겹쳤다. Gateway는 인증 요청마다 Redis로 로그아웃 토큰을 확인하는데, 이 호출이 50ms를 넘는 비율이 높으면 차단기가 10초 열리고 그동안 인증 요청을 모두 503으로 돌려준다(fail closed, ADR 0005). 노트북 한 대에서 k6와 전체 스택이 CPU를 나눠 쓰는 환경 영향으로 본다(TS-41과의 관계는 미확인).
- 해결: 측정 문서의 해석을 고쳤다([k6-seathold-bottleneck-plan.md](design/reservation/k6-seathold-bottleneck-plan.md) 5-2절 고침, 5-4절). 503을 출처별로 나누는 방법을 k6 README에 적었다. Eureka 이탈이 로그로 확인된 것은 P0(5-1절)뿐이다.
- 배운 점: 같은 상태 코드도 출처가 여럿이다. 503은 로그(`No servers available`)와 차단기 지표로 출처를 먼저 가른 뒤 해석한다.

### TS-43 측정 중 컨테이너 안에서 `java`를 실행하자 앱의 GC 로그가 `gc.log.0`으로 밀려났다
- 서비스·작성: reservation / kwonwoo / 2026-11-05
- 문제: G1 측정(P0'') 뒤 `/tmp/gc.log`를 읽으니 한 줄뿐이었다. Git Bash에서는 `docker exec ... cat /tmp/gc.log`가 Windows 경로로 바뀌어 파일을 찾지 못하기도 했다.
- 원인: 컨테이너 안에서 설정 확인용으로 `java -XX:+PrintFlagsFinal`을 실행했다. 이 프로세스가 `JAVA_TOOL_OPTIONS`(`-Xlog:gc...:file=/tmp/gc.log`)를 물려받아 같은 파일을 새로 열면서 앱의 로그를 `gc.log.0`으로 돌렸다. 경로 문제는 Git Bash(MSYS)가 `/tmp`로 시작하는 인자를 Windows 경로로 바꾸기 때문이다.
- 해결: 앱의 로그는 `gc.log.0`에 그대로 있어 그 파일로 분석했다. `MSYS_NO_PATHCONV=1`을 붙여 읽었다. 두 주의점을 `reservation-service/k6/README.md` 3절에 적었다.
- 배운 점: `JAVA_TOOL_OPTIONS`는 그 컨테이너에서 뜨는 모든 JVM에 붙는다. 측정 중인 컨테이너에서는 다른 JVM을 띄우지 않거나, 띄울 때 `JAVA_TOOL_OPTIONS=`를 비운다.

### TS-44 기록 검사 훅(`records-check.sh`)이 Windows에서 한 번에 약 93초가 걸려 훅 시한(30초)을 넘겼다
- 서비스·작성: 공통(에이전트 훅·CI) / kangmin / 2026-11-09
- 문제: 에이전트가 응답을 끝낼 때마다 도는 `Stop` 훅(`script/records-check.sh --hook`)의 시한은 30초인데, Windows Git Bash에서 한 번에 92.8초가 걸렸다(dev 복제본, 3회 중앙값 93.7/92.8/88.4초). 손으로 돌릴 때도 `timeout 100`을 붙여야 했고, 다른 세션에서는 2분 제한을 넘겨 백그라운드로 밀려났다.
- 원인: 파일마다 외부 프로세스를 여러 번 띄웠다. Windows Git Bash는 프로세스 하나를 띄우는 데 약 26ms가 든다(`/usr/bin/true` 200번 평균, 2026-11-09 이 PC).
  - 판단 기록(138개)은 머리말 키마다 `awk`를 따로 띄웠다(파일당 약 12번).
  - 비밀값 검사는 추적·새 파일(약 1,800개)마다 `grep`을 한 번씩 띄웠다.
  - 합치면 약 3,500번이고, 26ms를 곱하면 약 90초다. 이 합계는 추정이고, 측정한 92.8초와 크기가 맞는다.
- 해결(ISE-220):
  - 머리말은 파일당 `awk` 한 번으로 필요한 키를 모두 읽는다.
  - 색인은 한 번 읽어 bash 문자열 검색으로 찾는다.
  - 비밀값은 `git grep --untracked` 한 번으로 본다.
  - macOS 기본 bash 3.2에서도 돌도록 연관 배열을 쓰지 않았다.
  - 결과는 3.6초다(3회 중앙값 3.9/3.6/3.6초, 약 25배).
- 검증: 기록 구조를 일부러 망가뜨린 변이 31가지(머리말·필수 키 9개·verdict 값·색인·일지·공통 표·학습 노트 색인·파일 이름 규칙·비밀값·`--hook` 3가지)에서 원래 스크립트와 출력·종료 코드를 바이트 단위로 비교했다. 30가지가 같았고, 1가지는 원래 스크립트의 누락이 고쳐진 것이었다(아래).
- 함께 고친 누락: 원래 방식(`git ls-files` + `[[ -f ]]`)은 한글 이름 파일의 경로를 git이 `"docs/\353..."`처럼 따옴표로 감싸 내보내 파일로 찾지 못했다. 그래서 그 파일의 비밀값을 건너뛰었다. 새 방식은 `git grep`으로 잡고, `-c core.quotePath=false`를 붙여 경로를 한글 그대로 보여 준다.
- 남은 한계: 머리말을 닫는 `---`가 없으면 원래·새 스크립트 모두 본문 끝까지 머리말로 읽어 통과시킨다(이번 범위 밖). Linux CI와 macOS bash 3.2에서는 이 PR 전에 실행하지 않았다.
- AI 활용: 원인 분석과 수정은 Claude Code가 했고, 동작이 같은지는 다른 에이전트(herdr 작업자)가 저장소 밖 복제본에서 따로 검증했다. 판단 기록 `docs/ai-judgments/kangmin/2026-11-09-agent-token-k6-output-and-hook.md`, `docs/ai-judgments/kangmin/2026-11-09-records-check-25x-faster.md`.
- 배운 점: Windows Git Bash에서 셸 스크립트의 실행 시간은 대개 "띄운 프로세스 수 × 수십 ms"로 정해진다. 파일마다 도는 반복문 안에서는 외부 명령을 띄우지 말고, 한 번에 처리하는 도구(`git grep`, `awk` 한 번)로 바꾼다. 검사 도구를 고칠 때는 일부러 틀린 입력을 넣어 전후 출력을 비교해야 검사가 약해지지 않았음을 보일 수 있다.

### TS-45 리눅스 부하 발생기에서 k6가 결과 파일을 만들지 못했다(`permission denied`)
- 서비스·작성: loadtest / kangmin / 2026-11-09
- 문제: 배포 서버 측정용 발생기(EC2, Ubuntu)에서 `loadtest/k6/run.sh`를 실행하자 k6가 시작하자마자 멈췄다. 로컬 Windows에서는 같은 스크립트가 늘 동작했다.
  ```
  could not create the 'csv' output: open /scripts/results/l1-lottery-entry-...-raw.csv.gz: permission denied
  ```
- 원인: `grafana/k6:1.0.0` 컨테이너는 이미지 안의 사용자 `k6`(uid 12345)로 돈다(`docker run --entrypoint id grafana/k6:1.0.0`로 확인). 마운트한 `loadtest/k6/results`는 발생기의 `ubuntu`(uid 1000) 소유이고 권한이 755라, uid 12345는 쓸 수 없다. Windows Docker Desktop은 마운트 폴더의 리눅스 권한을 따지지 않아 로컬에서는 드러나지 않았다.
- 재현: Docker 볼륨에 uid 1000 소유·755 폴더를 만들고 k6 이미지로 파일을 쓰면 `Permission denied`, `--user 1000:1000`을 붙이면 성공했다.
- 해결(ISE-225): `run.sh`가 리눅스에서만 `docker run --user "$(id -u):$(id -g)"`를 붙인다. Windows(Git Bash)는 `uname -s`가 `MINGW64_NT-...`라 붙이지 않고 기존대로 돈다(로컬에서 summary·CSV 생성 확인).
- 우회: 고치기 전에는 `chmod a+w ~/<영문 이름>/iseonjwa/loadtest/k6/results`로 열었다.
- 배운 점: 컨테이너에 호스트 폴더를 마운트해 쓰게 할 때는 컨테이너 안 사용자(uid)와 폴더 소유자가 맞는지 본다. Windows·macOS의 Docker Desktop은 권한을 감춰서 리눅스 서버에서 처음 터진다. 측정 환경은 실제와 같은 OS에서 먼저 짧게 시험한다.

### TS-46 건별 알림 템플릿 검사가 Linux CI에서만 `amtool: error: open /data/event.json: permission denied`로 실패했다
- 서비스·작성: infra / junhyun / 2026-11-09
- 문제: #397(ISE-183)의 `script/check-prometheus-rules.sh`가 macOS 로컬에서는 통과하고, GitHub Actions(promtool 검사)에서만 `amtool: error: open /data/event.json: permission denied`와 "건별 알림 꼬리말이 그려지지 않는다"로 실패했다.
- 원인: 스크립트가 `mktemp -d`로 만든 폴더에 JSON을 쓰고 `docker run -v "$data_dir:/data:ro"`로 amtool 컨테이너에 넘겼다. `mktemp -d`는 폴더를 0700으로 만들고, `prom/alertmanager` 이미지의 amtool은 `nobody`로 돈다. Linux에서는 폴더 주인(러너 사용자)이 아닌 `nobody`가 폴더에 들어가지 못한다. macOS Docker Desktop은 파일 공유 계층이 권한을 다르게 넘겨 로컬에서는 드러나지 않았다.
- 해결: 폴더를 `chmod 755`, 파일을 `chmod 644`로 열었다(`script/check-prometheus-rules.sh`, 커밋 230f329b, #397에 포함).
- 배운 점: 임시 폴더를 컨테이너에 마운트할 때는 컨테이너 안 사용자가 누구인지 보고 권한을 연다. macOS 로컬 통과가 Linux CI 통과를 보장하지 않는다.

### TS-47 화면을 경로별로 나눈(route lazy) 뒤 일부 화면 테스트가 CI에서만 요소를 찾지 못했다
- 서비스·작성: frontend / junhyun / 2026-11-09
- 문제: #400(ISE-212)에서 홈·공연 상세 밖의 화면을 route `lazy`로 바꾼 뒤였다. 로컬에서는 전부 통과했는데, CI에서는 `PasswordChangePage`·`SignupPage` 테스트가 `Unable to find an accessible element with the role "link" and name "바꾸지 않고 돌아가기"`, `Unable to find a label with the text of: 이메일`로 실패했다.
- 원인: 테스트 도우미 `renderRoutesReady`는 `router.state.initialized`만 기다렸다. 라우터 상태가 먼저 바뀌고 React는 화면을 transition으로 나중에 그려서, 느린 CI에서는 그 사이에 `getBy`가 실행됐다. lazy 경로로 옮긴 직후에 `getBy`로 확인하는 곳도 같은 경쟁이 있었다.
- 해결: `renderRoutesReady`가 자리표시(`role="status"`, "화면을 불러오는 중")가 사라질 때까지 함께 기다리게 했다. lazy 경로로 옮긴 직후의 확인 네 곳은 `findBy`·`waitFor`로 바꿨다(`frontend/src/test/render.tsx`, 커밋 504a74ab, #400에 포함). 나중에 dev를 합칠 때 디자인 묶음이 더한 대기열 테스트도 같은 이유로 `renderRoutesReady`로 바꿨다.
- 배운 점: 라우터 상태와 DOM은 같은 순간에 바뀌지 않는다. lazy 경로를 쓰는 화면 테스트는 화면에 보이는 것(자리표시가 사라짐, `findBy`)으로 기다린다.

### TS-48 Windows에서 시각을 비교하는 reservation-service 테스트 4건만 실패
- 서비스·작성: reservation / kwonwoo / 2026-11-09
- 문제: `:reservation-service:test`에서 `LotteryPaymentMetricsTest`, `QueueApiTest`(토큰 발급), `ReservationApiTest`, `SeatHoldApiTest`의 시각 테스트 한 건씩 실패했다. 다시 돌려도 같았다. 실패 값이 몇 초씩 어긋나 있었다(`추첨 5분 뒤 반영한 시간이어야 한다: 294.1944835`, `PT10M5.3636334S`).
- 원인: 테스트가 시작 시각은 컨테이너(PostgreSQL `now()`, Redis `TIME`)에서, 비교 기준은 호스트 JVM(`Instant.now()`, 앱 `Clock`)에서 받는다. Windows 시계가 실제보다 약 7초 늦었고(Windows 시간 서비스 `w32time` 정지), Docker Desktop VM 시계는 맞았다. 외부 시각(`www.google.com`의 `Date` 헤더)과 비교해 Windows만 틀린 것을 확인했다. 컨테이너에서 `hwclock -s`를 실행해 봤지만 차이가 그대로였다.
- 해결: 관리자 PowerShell에서 `Start-Service w32time`, `w32tm /config /manualpeerlist:"time.windows.com,0x9" /syncfromflags:manual /update`, `w32tm /resync /force`로 시계를 맞췄다. 다시 돌리니 374건 중 실패 0건이었다(건너뜀 11건).
- 배운 점: 시간 테스트가 로컬에서만 실패하면 호스트·컨테이너·외부 시각 세 개를 먼저 비교한다. 테스트는 비교 양쪽을 같은 시계에서 얻거나 경계에 여유를 둔다. 자세히: [docs/learning/kwonwoo/qna/2026-11-09-host-clock-drift-test-failures.md](learning/kwonwoo/qna/2026-11-09-host-clock-drift-test-failures.md)

### TS-49 로컬 스택을 켜 둔 Mac이 잠자는 동안 슬랙 성능 채널에 결제 작업 정지 알림이 반복해서 왔다
- 서비스·작성: infra / junhyun / 2026-11-10
- 문제: 11/10 00:38~12:43 슬랙 `#이선좌-성능-모니터링`에 `PaymentJobStalled`(reconciliation, `*-cleanup`, gauge-refresh)가 울렸다 꺼지기를 반복했다. 배포 서버에서 온 것인지 로컬에서 온 것인지 몰랐다.
- 원인
  - 보낸 곳은 로컬이었다. 로컬 Alertmanager의 `alertmanager_notifications_total{integration="slack"}`은 12(11/9 13:29 시작 뒤, 실패 0), 배포 서버는 0이었다. 로컬 `.env`에 `SLACK_WEBHOOK_MONITORING`이 들어 있어 로컬 알림도 같은 채널로 갔다.
  - Mac은 07:20~13:00에 잠자기였고, Power Nap(`pmset -g`의 `powernap 1`)으로 15~17분마다 몇 초에서 1분쯤 깨어났다(`pmset -g log`의 DarkWake). 알림이 켜진 시각은 모두 DarkWake 뒤 1~2분 안이었다(01:26→01:28, 08:42→08:43, 12:40→12:43 등).
  - 잠자는 동안 Docker VM이 멈춰 결제 배경 작업이 돌지 않는다. 깨어나면 벽시계(`time()`)만 잠잔 시간만큼 앞서 있어 `time() - payment_job_last_success_timestamp`가 기준(reconciliation 15분, 정리 작업 1시간)을 한꺼번에 넘는다. 규칙이 `for: 0s`라 첫 평가에서 바로 켜진다.
  - 밤의 DarkWake는 약 45초로 Alertmanager `group_wait`(30초)보다 길어 실제로 보내졌다. 13:00에 완전히 깨어난 뒤에는 모든 작업의 마지막 성공이 5~85초 전이었고 알림이 켜지지 않았다.
  - 배포 서버도 같은 기간에 `PaymentJobStalled`(gauge-refresh)·`PaymentOutboxRelayStalled`가 15~30초씩 켜졌지만 `group_wait` 안에 꺼져 보내지 않았다.
- 해결: 로컬 `.env`의 `SLACK_WEBHOOK_MONITORING`을 비우고(원래 값은 같은 파일에 주석으로 남김) `docker compose -f compose.yml -f compose.local.yml up -d --force-recreate --no-deps alertmanager`로 다시 만들었다. 컨테이너 안 웹훅 파일이 자리표시 주소(`slack-webhook-not-set`)로 바뀌고 `/-/ready`가 200인 것을 확인했다. 규칙(`for: 0s`)은 바꾸지 않았다.
- AI 활용: 원인 조사와 대책 세 가지(로컬 웹훅 끄기, 안 쓸 때 로컬 스택 끄기, 규칙에 `for: 1m`)는 Claude Code가 냈고, 사용자가 로컬 웹훅 끄기를 골랐다(`docs/ai-judgments/junhyun/daily/2026-11-10.md`).
- 배운 점: 슬랙 알림의 "환경" 칸(Prometheus 외부 라벨 `env`, 로컬은 "로컬")을 먼저 본다. 벽시계와 마지막 성공 시각을 비교하는 규칙은 노트북 잠자기나 VM 정지 뒤 바로 켜진다. 로컬 `.env`에 팀 채널 웹훅을 넣어 두면 로컬 알림도 팀 채널로 간다.

### TS-50 측정 모드를 켠 직후 첫 관리자 로그인이 503 `SERVICE_UNAVAILABLE`이었다
- 서비스·작성: user-service / kangmin / 2026-11-10
- 문제: 배포 서버에서 측정 모드를 켠 직후 `script/deploy/seed-demo.sh`의 첫 관리자 로그인(`POST /api/v1/auth/login`, 206행)이 503 `SERVICE_UNAVAILABLE` "일시적으로 서비스를 사용할 수 없습니다"로 실패했다. gateway 로그상 이 요청은 1,164ms 걸렸다. Redis는 메모리 약 25MB, AOF 정상, OOM 아님으로 확인했다.
- 원인: 미확인. 다음은 코드로 확인한 사실이다.
  - 로그인은 `AuthService.login`에서 Refresh를 `sessions.save`로 Redis에 저장한다(`user-service/src/main/java/com/iseonjwa/user/application/AuthService.java:76`).
  - `RedisSessionStore.save`는 `DataAccessException`을 `CommonErrorCode.SERVICE_UNAVAILABLE`(503)로 바꾼다(`user-service/src/main/java/com/iseonjwa/user/infrastructure/security/RedisSessionStore.java:44-49`, 99-101행).
  - Redis 명령 시한은 `timeout: 100ms`다(`config-repo/application.yml:51`).
  - 재기동 직후 첫 요청에서 Redis 명령이 100ms를 넘겼다는 것은 추정이다. 503이 실제로 이 `save`에서 났는지, 1,164ms 중 어느 부분이 Redis 대기인지는 확인 못 함이다.
- 해결: 우회. 같은 로그인을 다시 실행해 성공했고, 이후 같은 측정 세션에서 같은 현상은 다시 나지 않았다. 코드와 설정은 바꾸지 않았다. 측정 기록은 [numbers-and-measurement.md](design/lottery/numbers-and-measurement.md) 3-3절 "운영 메모"에 있다.
- 배운 점: Gateway 쪽 같은 증상은 TS-17에서 기동 시 PING으로 예열해 막았지만, user-service의 Redis 연결은 그 예열 대상이 아니다. 측정 모드처럼 서비스를 한꺼번에 다시 띄운 직후의 첫 요청 실패는 한 번 재시도하고, 재시도 성공을 기록에 남긴다. `seed-demo.sh`에 로그인 재시도를 두는 것은 제안이며 아직 반영하지 않았다.

### TS-51 L1을 웜업 없이 초당 800건으로 시작하자 Gateway `auth-redis` 차단기가 15초 만에 열렸다
- 서비스·작성: gateway / kangmin / 2026-11-10
- 문제: 배포 서버 병목 탐색 C1(L1 응모 몰림, 웜업 없이 초당 800 → 1600 → 2400 → 3200, 단계마다 65초)에서 응답 대부분이 `auth_unavailable` 503이었다. 단계별 비율은 55% · 97% · 99% · 99%다. 차단기는 시작 15초 뒤(03:24:00쯤) 느린 호출 100%로 열렸고 이후 열림을 반복했다. 그동안 reservation-service CPU 2%, Postgres 0.3%로, 요청은 서비스에 닿기 전 Gateway에서 거절됐다.
- 원인(추정): `auth-redis` 차단기 설정(`config-repo/gateway.yml:58-65`)은 건수 기반 창 20, 최소 호출 20, 느린 호출 50ms·50%, 열림 10초, 반열림 5건이다. 20건은 초당 1,000건에서 0.02초 분량이라(계산값) 순간 지연 몇 번으로 창의 절반이 느린 호출이 되고, 10초 동안 인증 요청 전체를 거절한다. 다시 닫히면 몰린 요청으로 곧 다시 열린다. 웜업 뒤 단계 측정(C2)에서는 초당 800까지 거절 0%, 1000에서 19%였고 첫 열림은 1000 단계 끝이었다. 느린 호출이 Redis 자체 지연인지 Gateway 쪽 대기(CPU 포화 등)인지는 확인 못 함이다.
- 해결:
  - 측정 방법: 웜업(100·200·400) 뒤 단계를 재도록 바꿨다.
  - 설정 시험: 측정 모드 안의 임시 덮어쓰기로 측정 기반 차단기(시간 창 10초, 최소 500건, 느린 호출 100ms·80%, 열림 5초, 반열림 20건)에 인증 벌크헤드 100, reservation Hikari 20을 함께 적용했다(E). 초당 1,400까지 차단기가 열리지 않았다. 다만 1200 이상에서는 벌크헤드가 넘친 요청을 거절했고(1400에서 1.9%), p95 300ms 기준 한도는 약 초당 1,000이다(1200에서 339ms).
  - 운영 반영: 하지 않았다. 측정 뒤 운영 설정으로 되돌렸고(04:13 측정 모드 off), `config-repo`는 바뀌지 않았다. 운영 반영은 `contract` 이슈로 올릴 예정이며 아직 등록하지 않았다.
  - 근거 수치는 [numbers-and-measurement.md](design/lottery/numbers-and-measurement.md) 3-4절 C1·C2·C3~E다.
- 배운 점: 건수 기반 작은 창은 처리량이 높을수록 아주 짧은 시간의 지연에 반응한다. 차단기 창은 예상 처리량과 함께 정하고, 측정은 웜업 뒤 단계로 올려 콜드 시작 효과와 한계를 따로 본다. 503의 출처를 차단기 지표로 먼저 가르는 방법은 TS-42에 있다.
- 이어진 고민: 원인 기록(#463), 이중 기록 발견, 시한·벌크헤드 재측정 값까지의 흐름은 [gateway-auth-redis-resilience.md](design/infra/gateway-auth-redis-resilience.md)에 모았다.

### TS-52 k6 setup 로그가 `results/*-stdout.txt`에 남지 않았다
- 서비스·작성: loadtest / kangmin / 2026-11-10
- 문제: 배포 서버 측정(L2-a 1회)에서 k6 setup의 `console.log`(빌링키 발급·응모 진행)를 결과 파일에서 찾을 수 없었다.
- 원인: `loadtest/k6/run.sh:75`는 `"/scripts/${SCRIPT}" | tee "$K6_DIR/results/${NAME}-stdout.txt"`로 stdout만 `tee`에 넘긴다. k6 콘솔 로그는 stderr로 나가므로 파일에 남지 않는다.
- 해결: 우회. 2회부터는 tmux 화면(`tmux capture-pane`)으로 setup 로그를 확인했다. `run.sh`는 고치지 않았다.
- 배운 점: 콘솔 로그까지 파일에 남기려면 `2>&1 | tee ...`처럼 stderr도 합쳐야 한다. `run.sh` 수정은 제안이며, 기본 `--quiet`(51행)라 진행 표시줄은 꺼져 있지만, `K6_PROGRESS=1`로 켜면 진행 표시줄도 파일에 섞이는지(49행 Note, ISE-219) 함께 확인해야 한다.

### TS-53 생성자가 두 개인 `@Component`가 배포 서버에서 `No default constructor found`로 Gateway 기동을 막았다
- 서비스·작성: gateway / kangmin / 2026-11-10
- 문제: main 머지(`0e6d3581`, ISE-230 포함) 뒤 Jenkins 배포가 실패하고 `deploy.sh`가 직전 커밋 `f06c7d08`로 되돌렸다. GitHub Actions의 빌드·테스트·이미지는 모두 통과했다. 되돌릴 때 컨테이너를 새로 만들어 `docker logs`에는 실패 로그가 없었고, Loki에서 `Error creating bean with name 'authRedisFailures' ... Failed to instantiate ...: No default constructor found`(09:48:45 UTC)를 찾았다.
- 원인: `AuthRedisFailures`에 운영용 public 생성자 `(MeterRegistry, CircuitBreakerRegistry)`와 테스트용 package-private 생성자 `(..., LongSupplier)`를 두고 `@Autowired`를 붙이지 않았다. 생성자가 하나면 스프링이 그것을 쓰지만, 여럿이고 `@Autowired`가 없으면 기본 생성자를 찾다 실패한다. 단위 테스트는 `new`로 만들었고, 컨텍스트 테스트는 이 빈을 등록하지 않았으며, Gateway에는 앱 전체를 띄우는 테스트가 없어 CI가 잡지 못했다.
- 해결: 운영용 생성자에 `@Autowired`를 붙였다. `AuthRedisResilienceContextTest`에 `withUserConfiguration`으로 두 빈(`AuthRedisFailures`, `AuthRedisBreakerEvents`)을 등록해 만들어지는지 보는 테스트를 더했다. `@Autowired`를 빼면 이 테스트가 `No default constructor`로 실패하는 것을 확인했다(변이 테스트). Gateway 테스트 100건 통과, 로컬 Config 서버·Redis로 Gateway 기동도 확인했다.
- 배운 점: 테스트용 생성자를 따로 두면 스프링이 어느 생성자를 쓸지 정해 줘야 한다. `ApplicationContextRunner.withBean(Class)`은 public 생성자를 바로 골라 이 오류를 숨기므로, 컴포넌트 스캔과 같은 경로를 보려면 `withUserConfiguration`을 쓴다. 되돌림 뒤에는 실패한 컨테이너 로그가 사라지니 Loki에서 찾는다.

### TS-54 Zipkin 힙이 차서 unhealthy가 되자 배포의 `compose up --wait`가 매번 실패하고 되돌림까지 실패했다
- 서비스·작성: infra / kangmin / 2026-11-11
- 문제: 11/10 14:53Z(#471)와 15:27Z(#474) main 배포가 두 번 모두 실패해 `3b8b2bc9`로 되돌려졌다. GitHub Actions와 이미지 받기는 성공했고, 앱 컨테이너는 죽지 않았다. 두 번 모두 마지막 앱 컨테이너가 뜬 직후(14:54:09, 15:28:12) compose가 상태 확인 요청을 끊었고(dockerd "request cancelled by client"), 되돌림도 컨테이너가 뜬 1~2초 뒤 Jenkins SSH 세션이 끝났다(되돌림도 실패, `deploy.sh` 종료 코드 3으로 보임). 처음에는 Jenkins 메모리 부족을 의심했다.
- 원인: Zipkin이 `java.lang.OutOfMemoryError: Java heap space`로 GC만 돌며(CPU 101%) 상태 확인 5초 시한을 넘겨 `unhealthy`였다(재시작 4회). `deploy.sh`의 `docker compose up --wait`는 프로젝트 안에 unhealthy 컨테이너가 하나라도 있으면 바로 실패한다. Zipkin은 설정이 바뀌지 않아 배포 때 다시 만들어지지 않으므로 고장 난 채 남아 배포와 되돌림을 모두 막았다. 힙이 찬 것은 메모리 저장 기본 한도 50만 건(`zipkin-server-shared.yml`의 `max-spans: ${MEM_MAX_SPANS:500000}`)이 힙 384m보다 크고, 평시 샘플링 1.0과 11/10 대량 측정으로 추적이 쌓였기 때문으로 보인다(건당 크기는 재지 않았다).
- 해결: Zipkin을 재시작해(15:33Z) healthy로 되돌리고, `compose.deploy.yml` zipkin에 `MEM_MAX_SPANS: 100000`을 넣었다. 설정이 바뀌어 다음 배포에서 Zipkin이 새로 만들어진다.
- 배운 점: `--wait` 배포는 앱이 아닌 부가 컨테이너(관측 도구)의 건강도 배포 성공 조건으로 삼는다. 되돌림이 실패하면 Jenkins 세션이 일찍 끝나 Jenkins 쪽 문제처럼 보이므로, 배포 서버에서 `docker ps`의 unhealthy와 `RestartCount`를 먼저 본다. 메모리에만 쌓는 저장소는 힙에 맞춘 건수 상한을 둔다.

### TS-55 같은 사용자가 탭 여러 개로 동시에 선점하면 매수 한도 4매를 넘어 8매까지 잡힌다
- 서비스·작성: reservation / kwonwoo / 2026-11-10
- 문제: 동시성 테스트 C4(같은 사용자, 탭 4개 동시 선점, 30라운드)에서 30라운드 모두 한도(4매)를 넘었고 최대 8매를 보유했다. k6 부하 측정에서는 사용자가 요청을 하나씩만 보내 드러나지 않는다.
- 원인: 선점이 사용자별 매수를 읽고 한도를 검사한 뒤 더했다. 읽기와 더하기 사이에 같은 사용자의 다른 요청이 끼어들어 둘 다 검사를 통과했다.
- 해결: S1. `INSERT ... ON CONFLICT DO NOTHING`으로 매수 행을 만든 뒤 `held_count + n <= max` 조건부 UPDATE로 검사와 반영을 한 문장에 묶고, 0행이면 `PURCHASE_LIMIT_EXCEEDED`로 끝낸다(SA 12-7 ①). 커밋 `5b2a9b4`. C4 30라운드 모두 4매 이하. 근거는 [concurrency-results.md](design/reservation/concurrency-results.md) 2·3절이다.
- 배운 점: "읽고 검사하고 쓴다"는 동시 요청 앞에서 깨진다. 검사 조건을 쓰는 문장의 WHERE에 넣으면 DB가 행 잠금으로 순서를 정한다.

### TS-56 같은 멱등키로 연타하면 자기 요청끼리 부딪혀 409를 받는다
- 서비스·작성: reservation / kwonwoo / 2026-11-10
- 문제: C5(같은 멱등키 3연타, 30라운드)에서 201이 30건, 자기 요청이 받은 409가 60건이었다. 더블 클릭이나 네트워크 재전송이면 사용자는 자기 좌석을 "이미 선점됨"으로 본다.
- 원인: 멱등키 기록을 선점 처리 끝에 남겼다. 같은 키의 두 요청이 모두 "처음 온 요청"으로 들어가 같은 좌석을 잡으려 했고, 뒤 요청이 좌석 경쟁에서 졌다.
- 해결: S2. 멱등키를 매수·좌석보다 먼저 `seat_hold_requests`(REQUESTED)로 INSERT하고 끝에 HELD로 바꾼다. PK 위반이면 트랜잭션 밖에서 새 읽기 트랜잭션으로 앞 요청 결과를 읽어 같은 응답을 준다. 커밋 `4eaa6d3`. C5 201 90건, 자기 요청 409 0건.
- 배운 점: 멱등 처리는 "같은 요청인지"를 가장 먼저 정해야 한다. 키 기록이 늦으면 같은 요청끼리 자원 경쟁을 한다.

### TS-57 잠긴 좌석을 노린 선점이 앞 트랜잭션이 끝날 때까지 끝없이 기다린다
- 서비스·작성: reservation / kwonwoo / 2026-11-10
- 문제: C3(좌석 잠금을 2초 쥔 트랜잭션 + 같은 좌석을 노린 5명, 3라운드)에서 p50 약 2,000ms, 최대 2,215ms를 기다렸다. 기다리는 동안 커넥션을 쥐어 다른 선점도 막는다.
- 원인: 좌석 UPDATE에 잠금 대기 상한이 없었다. 여러 좌석을 잠그는 순서도 요청마다 달랐다.
- 해결: S3. 멱등키 선점 뒤 `set_config('lock_timeout', '500ms', true)`를 걸고, 좌석 UPDATE를 `ORDER BY id FOR UPDATE` 하위 쿼리로 바꿨다. 잠금 실패는 409 `SEAT_ALREADY_HELD`다. 커밋 `3b9b0bf`. C3 p50 약 560ms, 모두 409. 다만 최대가 1,060~1,305ms로 목표 1,000ms를 넘었다. `lock_timeout`은 잠금 시도마다 따로 걸려(PostgreSQL 문서) 같은 행을 여럿이 기다리면 약 2배까지 기다릴 수 있다. 코드가 아니라 목표가 동작과 맞지 않아 목표를 1,500ms로 고쳤다(`971eec2`, concurrency-results.md 4-3절).
- 배운 점: `lock_timeout`은 문장 상한이 아니라 잠금 시도당 상한이다. 테스트 목표는 설정값이 아니라 그 동작에서 끌어낸다.

### TS-58 만료 스케줄러 두 개가 같은 만료 건을 서로 집어 헛수고한다
- 서비스·작성: reservation / kwonwoo / 2026-11-10
- 문제: C6(만료 100건, 인스턴스 2개)에서 집었지만 만료하지 못한 시도(헛수고)가 100건, 소요 1,191ms였다.
- 원인: 두 인스턴스가 같은 조회로 같은 만료 대상을 묶음으로 읽고, 한쪽이 처리하는 동안 다른 쪽은 잠금을 기다리다 이미 처리된 건을 다시 봤다.
- 해결: S4. 건별 트랜잭션 안에서 `FOR UPDATE SKIP LOCKED LIMIT 1`로 한 건씩 집는다. 잠긴 건은 건너뛰어 다른 인스턴스와 겹치지 않는다. 실패한 예매는 그 주기에서 뺀다. 잠금 순서(예매 → 좌석 → 매수)는 그대로다. 커밋 `c307621`. C6 헛수고 0, 733ms.
- 배운 점: 여러 작업자가 같은 큐 테이블을 나눠 처리할 때는 `SKIP LOCKED`로 집는다. k6 측정은 만료 스케줄러를 끄고 인스턴스 하나로 재므로 이 결함을 보지 못한다.

### TS-59 k6 메모리 상한 때문에 Gateway 인증 차단기 열림을 서버 밖 한계로 잘못 읽었다
- 서비스·작성: loadtest·gateway / kwonwoo / 2026-11-10
- 문제: 새 측정 기기(i9-14900HX, Docker VM 32코어·16GiB)에서 K1 S 1,280명 단계의 좌석 조회 성공이 3회에 81.1%·15.5%·44.1%로 흔들렸고, 실패는 모두 Gateway 인증 차단기(`auth-redis`)의 즉시 503이었다. K1-H 800명도 19~23%였다. 그래서 "남은 한계는 부하 생성기와 Gateway"로 판단하고 K1 개선을 P4c에서 멈췄다. Gateway를 우회한 K1-H 탐색 회는 k6가 `k6_exit` 137로 죽었다.
- 원인: 이전 기기(VM 7.6GiB)를 지키려 둔 k6 상한(`K6_MEMORY` 2.5GiB, `GOMEMLIMIT` 2GiB)이 새 기기에서 큰 단계(K1 S 640명 이상, K1-H 400명 이상)의 k6 메모리(약 1.9~2GiB)에 닿았다. k6가 GC로 멈췄다 요청을 몰아 보내 CPU를 16~24코어까지 썼다. 그 몰림이 Gateway의 Redis 확인을 순간적으로 몰아 차단기를 더 자주 연 것으로 본다(Gateway 내부는 확인하지 않음).
- 해결: `run.sh` 기본값을 `K6_MEMORY=6g`, `K6_GOMEMLIMIT=5632MiB`로 올렸다(측정 브랜치 `78a91fc`). 같은 조건에서 K1 S 1,280명이 100%(p95 32ms)가 되고 k6 CPU가 약 7코어로 내려갔다. F 3회에서도 1,280명은 2회 100%, 1회 81.6%였다. k6가 여유 있어도 차단기는 가끔 열리지만 훨씬 드물다. 상한 근처에서 잰 회의 해석은 측정 브랜치 `STEPS.md`와 [판단 기록](ai-judgments/kwonwoo/2026-11-09-k1-stop-at-p4c-skip-p4d.md)에서 정정했다.
- 덧붙임: `GOMEMLIMIT=5.5GiB`처럼 소수를 쓰면 k6가 시작하지 못하고 `k6_exit` 2로 끝난다. 정수와 단위(`5632MiB`)로 쓴다.
- 배운 점: 부하 생성기의 CPU·메모리를 서버 지표와 같이 본다. 생성기가 상한에 붙으면 요청 모양(몰림)이 바뀌어, 다른 구성 요소의 한계처럼 보이는 결과를 만든다. 기기를 바꾸면 측정 도구의 상한도 다시 정한다.
