# 테스트 작성·실행 가이드

API·인증·업무 동작과 앱 기동 정책은 Java·JUnit 테스트로 관리한다. 실행·환경 준비 도구에 같은 검증을 중복 구현하지 않는다.
개발 도구 자체도 `workspace-tools`의 Java 구현과 JUnit으로 관리한다. Python 설치 없이 JDK 21·Gradle·Docker Compose로 실행한다. 비밀값 파일의 0600 생성은 POSIX 파일 권한을 사용한다.
내부 테스트에서도 실제 PostgreSQL·Redis·HTTP를 사용할 수 있다. 검증 범위에 맞춰 다음 위치와 실행 태스크를 선택한다.

## 테스트 위치

| 범위 | 위치 | 실행 |
|---|---|---|
| 모듈 단위·서비스 통합 | 각 모듈의 `src/test/java` | `./gradlew test` 또는 모듈별 `:모듈:test` |
| dotenv·비밀값 파일·실행 환경·포트 정책·프로세스 종료 | `workspace-tools/src/test/java` | 기본 `test`·`build`에 포함 |
| 필수 설정 서버 장애 시 앱 기동 실패 | `system-tests/src/test/java`의 `ConfigServerUnavailableTest` | 기본 `test`·`build`에 포함 |
| 실행 중인 전체 스택의 API·연동·관측·장애 | 같은 모듈의 `WorkspaceSystemTest` | `:system-tests:systemTest`로 별도 실행 |

`system-tests`는 검증 전용 모듈이며 실행 서비스나 업무 JAR를 추가하지 않는다. 기존 애플리케이션 테스트는 각 서비스에 그대로 둔다.
IDE에서도 테스트 클래스를 실행할 수 있다. 저장소 루트는 작업 경로에서 찾으며, 설정 서버 기동 실패 검증 전에는 `:eureka:bootJar`가 필요하다. 시스템 테스트에는 실행 중인 local 스택과 `.env`가 필요하다.
기본 `test`는 `system` 태그를 제외하므로 전체 앱 스택이나 실제 `.env` 없이도 CI에서 실행할 수 있다. PostgreSQL·Redis가 필요한 서비스 통합 테스트의 인프라는 별도로 준비한다.

## 기본 검사

```bash
./gradlew verifyWorkspace --no-daemon
```

이 명령은 Java 도구의 포트 검사, 격리된 테스트 인프라 준비, 전체 빌드·JUnit, 기록·diff 검사를 조율한다. 도구와 설정 서버 기동 실패도 JUnit에 포함한다.
`script/check.sh`는 이 Gradle 태스크를 호출하는 호환 진입점이다. 기록·에이전트 설정용 기존 셸 도구에는 Python 의존성이 없다.

## 전체 스택 검사

[워크스페이스 가이드](workspace.md)에 따라 local Compose 스택을 기동한 뒤 실행한다.

```bash
./gradlew :system-tests:systemTest --no-daemon
```

Gateway 재기동의 첫 인증 요청과 Redis 장애·복구까지 확인하려면 다음 옵션을 쓴다. 해당 스택의 Gateway를 재기동하고 Redis를 잠깐 pause하므로 그 작업을 수행하는 검사에서만 지정한다.

```bash
./gradlew :system-tests:systemTest -PcoldStart=true -PredisFailure=true --no-daemon
```

테스트는 loopback의 local 스택과 개인 `.env`를 사용한다. 비밀값은 Compose의 dotenv 해석 결과로 읽으며 JUnit 출력·시스템 속성에 원문을 기록하지 않는다.
Redis pause는 `finally`에서 복구하고, JVM 종료 시에도 복구를 시도한다. 운영 환경·부하 용량·실제 PG의 검증을 대신하지 않는다.

## 기존 Python 검증의 이관 범위

| 기존 검사 | 현재 Java 테스트 |
|---|---|
| 회원가입·로그인·중복·잘못된 자격 정보 | `WorkspaceSystemTest`의 가입·로그인 응답 계약 |
| 위조 사용자 헤더 제거·서비스 신원 전달 | 위조 헤더 제거와 검증 신원 전달 |
| 관리자 경로 변형·인증 누락 | 관리자 경로 변형 거절 |
| 비밀번호 바이트 경계 | ASCII·UTF8 72바이트 경계 |
| JWT 서명·발급자·대상·종류, Refresh·로그아웃 | JWT 구분·회전·로그아웃 |
| 잠금·관리자 초기화·임시 비밀번호 변경 | 잠금·초기화·비밀번호 변경 |
| Kafka 진단 이벤트 전달 | Reservation → Kafka → Payment 진단 이벤트 |
| 내부 경로, Config 비밀값, Eureka 등록 | 각각의 시스템 테스트 |
| Prometheus·Grafana·Kafka-UI·동일 trace | 메트릭·대시보드·추적 연결 |
| Gateway 첫 요청, Redis pause·복구 | 선택 옵션을 사용하는 시스템 테스트 |
| Config Server가 없을 때 기동 실패 | `ConfigServerUnavailableTest` |

기존 `script/smoke-workspace.py`와 `script/check-config-failure.py`는 제거했다. 동작 판정과 테스트 리포트의 정본은 JUnit이다.
나머지 Python 환경·포트·JVM 도구와 `script/tests`도 제거했다. 공통 구현은 `workspace-tools/src/main/java`, 도구 회귀는 같은 모듈의 `src/test/java`에 있다.
dotenv는 Java에서도 Compose의 해석 결과를 사용한다. 파일 생성은 기존 값과 0600 권한을 유지하고, 실행 환경은 앱별로 필요한 비밀값만 전달한다.

## 개발 도구와 JVM 모드

```bash
./gradlew initLocalEnv
./gradlew checkPortExposure
./gradlew runLocal -Papp=user-service
```

`runLocal`은 선택한 앱의 JAR를 빌드하고 loopback으로 실행한다. 종료 시 자신이 시작한 JVM을 정리한다.
전체 JVM 모드는 워크스페이스 가이드에 따라 Compose 앱 포트를 비우고 Prometheus를 JVM 모드로 준비한 뒤 `./gradlew verifyJvm --no-daemon`으로 실행한다.
이 태스크는 앱 JAR 7개를 준비하고, Java가 시작한 JVM의 실제 소켓과 HTTP·Eureka 등록 주소·연동을 검증한다. 검사 후 소유한 JVM을 종료한다. 실제 소켓 확인에는 기존과 같이 `lsof`를 사용한다.

## 결과 확인

- 모듈 테스트: `모듈/build/reports/tests/test/index.html`.
- 시스템 테스트: `system-tests/build/reports/tests/systemTest/index.html`.
- 기동 실패 검증: `system-tests/build/reports/tests/test/index.html`.
- 도구 회귀: `workspace-tools/build/reports/tests/test/index.html`.
- JVM 모드: `system-tests/build/reports/tests/jvmSystemTest/index.html`.

시스템 테스트는 외부 실행 상태를 확인하므로 매번 실행한다. 서비스별 Mockito·DB 테스트와 시스템 검증을 서로 대체하지 않는다.
