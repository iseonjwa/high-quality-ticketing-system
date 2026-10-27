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
