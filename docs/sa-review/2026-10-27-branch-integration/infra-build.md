# 빌드·인프라·운영 통합 검토

검토일은 2026-10-27이다. **제안: 실행 기반은 Junhyun을 우선 후보로 두고, Kangmin의 빌드 관리와 서비스별 DB 계정 분리를 별도 이식한다.** 이는 팀 결정이 아니며 인증·공통 계약 검토와 함께 판단해야 한다. 실제 병합·코드 수정·커밋·push는 하지 않았다.

## 범위와 근거 표기

아래 별칭의 SHA를 고정했다. `J:경로:줄`, `K:경로:줄` 같은 표기는 해당 SHA의 파일과 한 줄 기준 위치를 뜻한다. 테스트는 현재 checkout 대신 각 SHA를 `git archive`로 추출한 `/tmp/ticketing-branch-review-<영문 이름>`에서 실행했다.

| 별칭 | 브랜치 작성자 | 고정 SHA |
|---|---|---|
| J | Junhyun | `45874b7904d98b667aad0737500aa3631bdda3e1` |
| K | Kwonwoo | `60994695c70fcb4e0c8c2ec11128fd7bb868de33` |
| H | Hyoseung | `acb862788e80ec602837afee8954baa1be528c02` |
| M | Kangmin | `965f000b6363170eab5761ea76def129a8394264` |
| Y | Youngeun | `86e2e66dfca4036ea4eb10387b695e48b5a4191f` |

관찰은 소스·실행 결과에 근거한다. 제안은 합의 전 선택지다. 이미지 태그의 현재 레지스트리 가용성과 운영 적합성은 이 검토에서 확인하지 않았다. live Notion의 확정·잠정 상태와 공식 버전 호환 근거는 [정본 대조](source-notes.md)를 함께 확인한다.

## 빌드와 서비스 구성

| 항목 | J | K | H | M | Y |
|---|---|---|---|---|---|
| Java / Boot / Cloud | 21 / 4.1.2 / 2025.1.4 | 동일 | 동일 | 동일 | 동일 |
| Gradle wrapper | 9.1.0, SHA256 있음 | 9.8.2, SHA256 없음 | 9.8.2, SHA256 없음 | 9.8.2, SHA256 있음 | 9.8.2, SHA256 없음 |
| 빌드 방식 | Groovy, java-library, BOM platform | Groovy, dependency-management | Groovy, dependency-management | Kotlin convention 플러그인 + version catalog + BOM platform | Groovy, dependency-management |
| 실행 앱 수 | 7 | 5 | 4 | 7 | 7 |
| 공통 라이브러리 | common / response / web / auth / messaging | common | common / response / web | common / response / web / messaging | common / response / web / messaging |
| 보조 모듈 | system-tests, workspace-tools | 없음 | 없음 | build-logic 포함 빌드 | 없음 |
| Gradle group / Java 패키지 | iseonjwa / iseonjwa.* | com.highquality / com.highquality.* | com.iseonjwa / com.iseonjwa.* | com.iseonjwa / com.iseonjwa.* | com.iseonjwa / com.iseonjwa.* |

J·M·Y의 앱은 Config/Eureka/Gateway/User/Reservation/Payment/Mock PG다. K는 `eureka-registry`·`gateway-edge`·세 업무 서비스이며 Config와 Mock PG가 없다. H는 Config/Eureka/Gateway/User만 포함하며 Reservation/Payment 라우트와 DB는 있으나 해당 실행 모듈은 없다.

근거: 각 SHA의 `settings.gradle:1`(M은 `settings.gradle.kts:1`), `build.gradle:1`(M은 `gradle/libs.versions.toml:1`, `build-logic/src/main/kotlin/iseonjwa.java-conventions.gradle.kts:14`), `gradle/wrapper/gradle-wrapper.properties:1`. group과 패키지 이름은 서로 다른 계약이며 전역 문자열 치환 대상으로 취급하지 않는다.

M의 장점은 공통 Java·MVC·JPA/Postgres·Boot 설정을 역할별 convention으로 나누고 의존성 버전을 catalog 한 곳에 모은 점이다. Config 저장 파일을 Test 입력으로 등록해 설정만 바뀌어도 재실행한다(`M:build-logic/src/main/kotlin/iseonjwa.spring-boot-service.gradle.kts:25`). Foojay로 JDK를 준비하며 Adoptium vendor를 고정한다(`M:settings.gradle.kts:6`, `M:build-logic/src/main/kotlin/iseonjwa.java-conventions.gradle.kts:16`).

J의 장점은 작업용 Java 도구와 격리 테스트·포트 정책·빌드·기록 검사를 한 태스크로 실행하는 점이다(`J:build.gradle:10`, `J:build.gradle:58`). 단, 검사 통과가 운영 용량이나 예매·결제 업무 완성을 뜻하지 않는다.

다섯 브랜치의 Gateway는 소스 기준 모두 `spring-cloud-starter-gateway-server-webflux`를 쓴다. Notion의 Gateway MVC 제안을 코드 현황으로 읽으면 안 된다(`K:gateway-edge/build.gradle:7`, 나머지는 각 SHA의 `gateway/build.gradle` 또는 `.kts`의 dependencies).

## Config·Eureka·라우팅의 의미 차이

| 항목 | J | K | H | M | Y |
|---|---|---|---|---|---|
| Config Server | native config-repo | 없음 | native config-repo | native config-repo | native config-repo |
| Config import | 필수, 테스트 문서에서 우회 | 로컬 파일 optional | Gateway/User 필수, 테스트 resources로 대체 | `!test`에서 필수, test에서 끔 | 모든 클라이언트 필수, 테스트 우회 없음 |
| Eureka 설정 위치 | config-repo | 모듈 내부 | 모듈 내부, Config 비의존 | config-repo, Config 필수 | config-repo, Config 필수 |
| Config 주소 환경 변수 | CONFIG_SERVER_URL | 해당 없음 | CONFIG_SERVER_URL | CONFIG_SERVER_URL | CONFIG_SERVER_HOST |
| Config 파일 기본 위치 | 환경 변수로 덮어씀 | 해당 없음 | file:../config-repo | file:/config-repo, bootRun에서 절대 경로 주입 | 현재/상위 폴더 config-repo |
| Gateway connect / response timeout | 1000ms / 3s | 명시 없음 | 1000ms / 3s | 1000ms / 3s | 500ms / 3s |
| 관리자 사용자 경로 | `/admin/users/**` 먼저 User로 | 전체 `/admin/**` Reservation | 전체 `/admin/**` Reservation | 전체 `/admin/**` Reservation | 전체 `/admin/**` Reservation |
| 알림 라우트 | `/notifications/**` 없음 | Reservation | Reservation | Reservation | Reservation |

근거: `J:config-repo/gateway.yml:8`, `K:gateway-edge/src/main/resources/application.yaml:10`, `H:config-repo/gateway.yml:16`, `M:config-repo/gateway.yml:9`, `Y:config-repo/gateway.yaml:8`. Config import는 각 SHA의 `gateway/src/main/resources/application.yml:1`(Y는 `.yaml:1`), H의 `gateway/src/test/resources/application.yml:1`, M의 `config-server/build.gradle.kts:9`, Y의 `config-server/src/main/resources/application.yaml:10`에서 확인했다.

H·M의 테스트 구성은 각각 다른 방식으로 Config/Eureka 의존을 끊는다. Y는 테스트 파일 자체가 없어 필수 import 실패를 검증하지 않는다. K의 `optional:file:../.env`는 Config Server optional 정책이 아니므로 두 의미를 혼동하면 안 된다.

**통합 전 의미 충돌:** 동일 `/api/v1/admin/**` 아래에서 사용자 관리 요청이 어느 서비스로 가는지 합의해야 한다. J의 관리자 경로 우선순위는 다른 Gateway 파일을 덮어쓰면 사라진다. 알림 API가 여전히 SA 범위라면 J에서 빠진 경로를 인증 정책과 함께 추가하는 안을 논의한다.

## 데이터·메시지·외부 포트

| 항목 | J | K | H | M | Y |
|---|---|---|---|---|---|
| PostgreSQL | 17-alpine digest, 서비스별 3 컨테이너 | 18 태그, 단일 DB 컨테이너 | 17.6-alpine, 3 컨테이너 | 18.6-alpine, 3 컨테이너 | Compose 없음 |
| DB 이름 / 계정 | user·reservation·payment / 공통 DB_USER | DB_NAME + 서비스 계정 변수 | *_db / *_service | *_db / *_app | DB 연결 설정 없음 |
| Redis | 8-alpine digest, AOF yes, 128MB noeviction | Compose 없음 | 7.4-alpine, 400MB noeviction | 8.10.2-alpine, 400MB noeviction, RDB/AOF off | 없음 |
| Kafka | 4.1.1 digest, internal9092 / host19092 | 없음 | 4.1.0, internal9092 / host29092 | 4.3.2, internal29092 / host9092, 자동 토픽 생성 off | 없음 |
| Gateway 외부 bind | 127.0.0.1:8080 | 앱 Compose 없음 | 모든 주소:8080 | 모든 주소:${GATEWAY_PORT:-8080} | 앱 Compose 없음 |
| DB/Redis 개발 포트 | explicit compose.local.yml, loopback | DB_PORT만 지정, host IP 미제한 | override 자동 병합, loopback | override 자동 병합, loopback | 없음 |
| 관리 UI | loopback | 없음 | loopback | loopback | 없음 |

근거: `J:compose.yml:143`, `J:compose.local.yml:16`, `K:docker-compose.yml:1`, `H:compose.yaml:121`, `H:compose.override.yaml:12`, `M:compose.yaml:11`, `M:compose.yaml:63`, `M:compose.yaml:83`, `M:compose.yaml:163`, `M:compose.override.yaml:1`. J의 DB/Redis 테스트 전용 컨테이너는 `J:compose.test.yml:1`에 별도로 있다.

K의 Compose는 서비스별 계정 환경 변수는 선언하지만 DB/role 생성용 init SQL 마운트나 스크립트가 없다. 선언한 환경 변수가 서비스 계정을 실제 생성한다는 근거는 없다(`K:docker-compose.yml:7`). healthcheck는 `pg-isready`로 쓰여 있어 이미지의 PostgreSQL 표준 도구 `pg_isready`와 다르다(`K:docker-compose.yml:23`). 이 Compose를 공통 기반으로 그대로 채택하지 않는 안을 제안한다.

M은 PostgreSQL 18의 상위 데이터 경로 마운트와 실제 18 컨테이너 테스트를 갖춘다. J/H의 17 볼륨을 18 경로로 이름만 바꾸어 재사용하는 계획은 없다. DB 버전·마이그레이션·계정 변경은 합의 후 새 환경에서 검증해야 한다.

Redis 비영속 여부는 모양만 다른 설정이 아니다. 인증 블랙리스트·Refresh 상태·대기열의 재기동 후 유실 가능성이 달라진다. M의 “기준 저장소가 아니다” 주석만으로 인증 상태까지 비영속해도 된다고 판단하지 않는다. J의 AOF도 내구성·복구 완료를 증명하지 않는다.

J의 `DB_PASSWORD` 공유와 H/M의 서비스별 DB 비밀번호는 이식 시 변수 이름만의 충돌이 아니다. 서비스별 자격 증명을 유지하면 구성 변수와 도구·테스트 준비 코드를 함께 바꿔야 한다. H의 `JWT_SECRET`과 J의 `JWT_ACCESS_SECRET`·`JWT_REFRESH_SECRET`도 발급·검증 방식 결정 후 맞춰야 한다.

## 관측·실행 도구·CI

| 항목 | J | K | H | M | Y |
|---|---|---|---|---|---|
| Actuator/Prometheus | 7앱, 관리9080 분리 | 5앱, 업무 포트 | 4앱, 업무 포트 | 7앱, 업무 포트 | Actuator 선언, Prometheus registry 없음 |
| Zipkin | 7앱 + Kafka observation | 없음 | 4앱 | 7앱 | 없음 |
| 수집 방식 | Compose 정적 목록 + JVM 전용 파일 | 수집기 없음 | Eureka 발견 + platform 정적 | Compose/host 정적 목록 | 수집기 없음 |
| 샘플링 local / loadtest / prod | 1.0 / 0.01 / 0.1 | 없음 | 기본1.0 / 0.1 / prod 없음 | 기본1.0 / 0.1 / 0.1 | 없음 |
| Docker 실행 사용자 | non-root, JDK/JRE digest | Dockerfile 없음 | non-root | non-root | Dockerfile 없음 |
| 빌드 CI | verifyWorkspace + always test infra down | 기록 검사만 | 기록 검사만 | 기록 검사만 | 기록 검사만 |
| 로컬 도구 | Java init-env/run-local/check-ports/JVM 검증 | 없음 | 셸 에이전트·기록 도구만 | 셸 에이전트·기록 도구만 | 셸 에이전트·기록 도구만 |

근거: `J:config-repo/application.yml:1`, `J:.github/workflows/build.yml:1`, `J:infra/prometheus.yml:1`, `J:compose.jvm.yml:1`, `K:build.gradle:58`, `H:build.gradle:52`, `H:infra/prometheus/prometheus.yml:1`, `M:build-logic/src/main/kotlin/iseonjwa.spring-boot-service.gradle.kts:13`, `M:infra/prometheus/prometheus.yml:1`, `Y:gateway/build.gradle:3`, `Y:build.gradle:29`와 각 SHA의 `.github/workflows/records-check.yml:1`.

Y의 config-repo는 `prometheus` endpoint 노출 이름을 적지만 해당 registry 의존성을 갖추지 않았다. YAML 선언만으로 지표가 수집된다고 보지 않는다. Gateway는 actuator `gateway`도 노출 대상으로 적는다(`Y:config-repo/gateway.yaml:31`); 실제 접근 정책과 노출 결과는 미검증이다.

H의 동적 수집은 앱 추가 시 수집 목록 수정을 줄인다. M의 정적 수집은 실행 주소를 읽기 쉽지만 Compose와 호스트 앱 목록을 함께 관리하며 실행 방식에 따라 일부 대상이 DOWN으로 남는다. J의 분리 관리 포트를 선택하면 기존 H/M healthcheck와 Prometheus target을 그대로 가져오면 안 된다.

## 실행 검증

2026-10-27 로컬에서 아래 고정 소스의 `bash gradlew build --no-daemon --console=plain`을 각각 실행했다. 소스 수정이나 테스트 목적 설정 변경은 하지 않았다. 기존 Docker 컨테이너의 pause·stop이나 기존 스택 포트 변경은 하지 않았다.

| 대상 | 결과 | JUnit XML 합계 | 검증 의미와 한계 |
|---|---|---|---|
| H | BUILD SUCCESSFUL, 43초, 35 tasks 실행 | 31 tests, 실패0·오류0·skip0 | 공통 응답·감사자, Config 저장소, Gateway 라우트/JWT/블랙리스트, User context 검증. 실제 전체 스택 연결 아님 |
| M | BUILD SUCCESSFUL, 1분20초, 55 tasks 실행 | 11 tests, 실패0·오류0·skip0 | Config/라우트·앱 기동과 User/Reservation/Payment Testcontainers PostgreSQL18·Flyway 검증. 사용자 인증·SAGA 검증 아님 |
| Y | BUILD SUCCESSFUL, 28초, 43 tasks 실행 | 0 tests, 전부 NO-SOURCE | Java 컴파일·JAR 생성 성공. API·DB·Config 기동·인증 검증 없음 |

H/Y의 저장소 `gradlew`는 Git 실행 권한이 0644라 직접 `./gradlew` 실행 전 권한 수정을 요구한다. 검증에서는 원본 모드를 유지하고 `bash gradlew`로 실행했다. M은 0755다. Y 빌드는 Gradle10 비호환 deprecation 경고를 출력했으며 현재 9.8.2 빌드는 성공했다.

실행 로그는 `/tmp/ticketing-hyoseung-build.log`, `/tmp/ticketing-kangmin-build.log`, `/tmp/ticketing-youngeun-build.log`다. JUnit 근거는 각 추출 폴더의 `*/build/test-results/test/TEST-*.xml`이다. `/tmp` 산출물은 임시 증거이며 저장소에 포함하지 않았다.

미검증: 다섯 브랜치 전체 Compose 기동, 이미지 전체 pull/build, 모니터링 실제 수집, 부하·복구·운영 용량, J/K의 이번 빌드 결과(다른 검토 파일 참조). M의 Testcontainers 결과가 M의 전체 Compose 배포 성공을 대신하지 않는다.

## 통합 제안과 비용

| 대상 | 제안 | 가져올 부분 / 이유 | 비용·주의점 |
|---|---|---|---|
| J 실행 기반 | 채택 후보 | 7앱·필수 Config·격리 테스트·관리 포트·CI가 한 경로로 연결됨 | Java 패키지·응답/인증 계약은 별도 비교 필요. 운영 검증 완료 의미 아님 |
| M 빌드 관리 | 수정 후 채택 후보 | catalog, convention, config-repo 테스트 입력, 실제 PostgreSQL18 테스트 | Groovy→Kotlin 전체 이식을 즉시 합치면 기존 스크립트·모듈 경계도 함께 변함. 인증 통합 뒤 별도 변경으로 검증하는 안 권고 |
| H 관측·테스트 | 선택 이식 | Eureka 동적 수집, 별도 test resources, 라우트 테스트 | J 관리9080/Config 실패 테스트와 주소·우회 정책 맞춤. 같은 검증 중복 추가는 제외 |
| H/M 계정 분리 | 논의 후 수정 | 서비스별 DB 사용자·암호를 명시함 | J 환경 생성기·Compose·DB URL·테스트를 함께 정비. 서비스 권한 최소화 여부 별도 검증 |
| Y 설정 | 제한적 참고 | 전체 모듈 이름·경로가 간단하고 Gateway 헤더 제거 선언이 보임 | J/H 필터와 중복·순서 확인. 관측/DB/테스트가 없어 실행 기반 전체 대체 근거 부족 |
| K Compose | 그대로 채택 제외 제안 | 단일 Postgres 개발 방식은 자원 절감 선택지로 참고 | healthcheck·계정 생성·포트 bind·서비스 격리를 먼저 수정·검증해야 함 |

## 팀 논의 안건

1. J의 통합 경로를 먼저 안정화하고 M 빌드 관리 전환을 분리할지 정한다. 도구 취향과 실행 계약 검증을 한 변경에 섞으면 회귀 원인을 찾기 어렵다.
2. PostgreSQL 17/18, Redis/Kafka 이미지 버전과 digest 고정 기준을 정한다. 현재 태그 존재 여부와 팀 환경 지원도 함께 확인한다.
3. Redis를 재시작했을 때 기존 Access/Refresh 상태를 어떻게 처리할지 정한다. 영속성은 인증·대기열 책임과 함께 판단한다.
4. 서비스별 DB 컨테이너/계정과 비밀번호 변수 계약을 정한다. 단일 컨테이너가 필요하면 DB·role 생성과 권한 분리의 실행 근거를 먼저 마련한다.
5. localhost 전용 개발 노출과 배포 Gateway 공개 방식, 관리 포트9080 분리를 정한다. Compose override 자동 병합 여부도 실행 가이드에 고정한다.
6. `/admin/users/**`·`/notifications/**` 소유 서비스와 Gateway 경로를 SA 정본 기준으로 확인한다.
7. Config 서버 실패 시 필수 기동 실패와 테스트 우회 범위를 유지할지 정한다. H의 비의존 Eureka와 나머지 Config 기반 Eureka 중 하나를 선택한다.
8. 기본 CI에 단위·DB 통합·Config 실패·기록 검사를 포함하고, 전체 스택·장애/부하 검사를 어느 단계에서 실행할지 정한다.
