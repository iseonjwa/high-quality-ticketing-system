# 초기 환경 브랜치 통합 분석과 통합 시안

- 날짜: 2026-10-27
- 작성: @GGangMY (Claude Code로 분석·통합)
- 대상: `origin/dev`(=main, 문서만) 이후 원격 브랜치 5개. 공통 조상은 모두 `4e02b5e`이고 dev보다 뒤처진 브랜치는 없다.
- 근거: 각 브랜치를 임시 worktree로 빌드·테스트한 결과(Windows 11, JDK 21.0.12, 로컬 인프라 꺼진 상태), `git merge-tree`로 계산한 충돌, `docs/core/SA MASTER.md` v3.1, Notion 결정 기록 #30(복제본, 10/27 확인)
- 상태: **팀 결정 전 시안.** 아래 "추천"대로 통합 브랜치 `ggangmy/chore/integration`을 만들어 검증했다(8절). 회의에서 결정이 달라지면 해당 커밋을 되돌리거나 바꾼다.

## 결정 필요 항목 요약

| 번호 | 쟁점 | 추천 (시안에 반영한 것) | 결정자 |
|---|---|---|---|
| D1 | 통합 기준 브랜치 | 준현 브랜치를 기준으로, 다른 브랜치에서 필요한 것만 옮긴다 | 팀 |
| D2 | 빌드 스크립트 DSL | Groovy (5개 중 4개, 기준 브랜치) | 팀 |
| D3 | Gradle 버전 | 9.8.2 (시안에서 체크섬 포함 적용, 전체 검사 통과) | 팀 |
| D4 | 패키지 이름 | 지금 하나로 확정. 시안은 기준 브랜치의 `iseonjwa.*` 유지 | 팀 (SA U12) |
| D5 | Gateway 방식 | WebFlux. 권우 브랜치도 16:06 커밋에서 WebFlux로 바꿔 5개 모두 같다 | 확인만 |
| D6 | 공통 모듈 경계 | 기준 브랜치의 5개 모듈 구성 유지. 권우 ADR 0013(하나로 + `compileOnly` 격리)은 대안으로 함께 검토 | 팀 |
| D7 | 테스트 인프라 방식 | `./gradlew build`만으로 통과하게. 시안은 user-service 테스트 전에 테스트용 compose를 자동으로 띄움 | 팀 |
| D8 | Windows 호환 | 통합 전 필수. 시안에서 3건 수정(8절) | 기준 브랜치 담당 |
| D9 | 개인 브랜치 안의 팀 규칙·SA 변경 | 코드와 분리해 별도 PR. 시안에서는 dev 상태로 되돌림 | 팀 |
| D10 | ADR·TS 번호와 중복 | 기준 브랜치 번호를 살리고 나머지는 이어 붙임(ADR 0013, TS-20~26) | 각 작성자 |
| D11 | SA 범위 밖 기능 | 로그인 잠금·관리자 초기화 등은 제안 상태로 두고 채택 여부를 따로 결정 | 팀 |

## 1. 브랜치 현황

| 브랜치 | 작성 | 커밋 | 변경 | 한 것 | 빌드·테스트 (직접 실행, Windows) |
|---|---|---|---|---|---|
| `codex/ise-5-junhyun-common-foundation` | 준현 | 35 | 184 파일, +8,672 | 공통 모듈 5개, Config·Eureka, User 인증 전체(가입·로그인·재발급·로그아웃·잠금·관리자 초기화), Gateway JWT·블랙리스트·AccessPolicy, compose 4종, CI, workspace-tools, ADR 0001~0012, TS-1~19 | `build`: 124개 중 21개 실패(인증 통합 20개는 테스트 인프라 미기동, 1개는 Windows 전용). `verifyWorkspace`: 1개 실패 + `checkRecords` 실패(둘 다 Windows 원인) |
| `hyoseung/chore/init-env` | 효승 | 9 | 81 파일, +3,291 | 공통 모듈 3개, Config·Eureka, Gateway JWT·블랙리스트·서킷, User 연결(인증 API 없음), compose, Dockerfile | 성공, 31개 통과 |
| `ggangmy/chore/init-env` | 강민 | 21 | 130 파일, +3,151 | 전체 모듈 뼈대(공통은 빈 모듈), Config·Eureka·Gateway 라우팅, compose 16개 컨테이너 + 관측, Testcontainers | 성공, 11개 통과 |
| `youngeun/chore/init-config` | 영은 | 1 | 78 파일, +957 | 전체 모듈 뼈대, reservation 하위 모듈 4계층 패키지, config-repo | 성공, 테스트 0개 |
| `kwonwoo/feat/common` | 권우 | 5 | — | Initializr 뼈대 → 16:06에 공통 응답·예외 처리 구현, Gateway WebFlux 전환, ADR 0001(하나의 common) 제안 | 16:06 기준 성공, 14개 통과 |

모든 테스트는 로컬 인프라를 끈 상태에서 다시 돌렸다. Config 서버가 떠 있으면 테스트가 우연히 통과할 수 있기 때문이다(TS-24).

## 2. 기준별 비교

| 기준 | 준현 | 효승 | 강민 | 영은 | 권우 |
|---|---|---|---|---|---|
| 빌드 DSL | Groovy | Groovy | Kotlin + 컨벤션 플러그인 + 버전 카탈로그 | Groovy | Groovy |
| Gradle | 9.1.0 | 9.8.2 | 9.8.2 | 9.8.2 | 9.8.2 |
| Java / Boot / Cloud | 21 / 4.1.2 / 2025.1.4 | 같음 | 같음 | 같음 | 같음 |
| 패키지 | `iseonjwa.*` | `com.iseonjwa.*` | `com.iseonjwa.*` | `com.iseonjwa.*` | `com.highquality.*` |
| 모듈 | common, -response, -web, -auth, -messaging + 앱 7개 + system-tests, workspace-tools | common, -response, -web + config·eureka·gateway·user | common 4개(빈 모듈) + 앱 7개 | common 4개 + 앱 7개 | common + 앱 5개 (Config·mock-pg 없음) |
| Gateway | WebFlux | WebFlux | WebFlux | WebFlux | WebFlux (16:06부터) |
| 설정 | Config 서버, `!test` 문서에서만 import + 재시도 | Config 서버 import + `.env` 직접 읽기 | Config 서버, `!test` 문서에서만 import | Config 서버 import | 서비스 공통 yaml + `.env` 직접 읽기 |
| 응답 형식 | SA #19 성공·실패 별도 형태 | SuccessResponse·ErrorResponse + traceId | 없음 | 없음 | ApiResponse·ErrorResponse (16:06) |
| 인증 | User 전체 + Gateway(AccessPolicy, 블랙리스트 fail-closed) + common-auth | Gateway 검증만 | 없음 | 없음 | 없음 |
| DB | 서비스별 PG 17 | 서비스별 PG 17.6 | 서비스별 PG 18.6 | — | PG 1개 + 계정 분리 |
| 테스트 DB | compose.test.yml(PG 17, Redis, 고정 포트 25433·26379) | — | Testcontainers PG 18 | — | H2 |
| 이미지 고정 | 태그 + `@sha256` 다이제스트 | 버전 태그 | 버전 태그 | — | — |
| CI | `verifyWorkspace` (PR, main·dev push) | — | — | — | — |

## 3. 충돌 계산

`git merge-tree --write-tree`로 두 브랜치씩 합쳤을 때 충돌 파일 수다(권우 14:42 커밋 기준). 머지는 실행하지 않았다.

| 조합 | 충돌 | 대표 파일 |
|---|---|---|
| 준현 + 효승 | 26 (문서 2) | `.env.example`, `Dockerfile`, `build.gradle`, 공통 모듈 build, config-repo |
| 준현 + 강민 | 26 (문서 1) | `.dockerignore`, `Dockerfile`, config-repo 전부 |
| 준현 + 영은 | 18 | `build.gradle`, 모듈별 build |
| 준현 + 권우 | 12 (문서 2) | `build.gradle`, wrapper, 서비스 build |
| 강민 + 영은 | 34 | Application 클래스, package-info |
| 강민 + 효승 | 20 | compose, config-repo |
| 효승 + 영은 | 13 | 모듈 build, Application 클래스 |
| 효승 + 권우 | 7 | `settings.gradle`, build |
| 영은 + 권우 | 9 | `settings.gradle`, 서비스 yaml |
| 강민 + 권우 | 4 (문서 1) | `.gitattributes`, wrapper |

의미적 충돌:
- Kotlin DSL과 Groovy가 섞이면 같은 폴더의 `settings.gradle`이 `settings.gradle.kts`보다 우선해 모듈이 조용히 빠지고 빌드는 성공한다(10/27 임시 폴더에서 확인).
- 패키지 이름이 셋이라 같은 역할의 클래스가 다른 경로로 공존할 수 있다.
- 공통 응답 클래스가 브랜치마다 다르다(`FailureResponse` / `ErrorResponse` / `ApiResponse`).
- WebFlux Gateway에 MVC가 들어오면 `MvcFoundOnClasspathException`, JPA가 들어오면 DataSource 설정 실패로 기동하지 않는다(10/27 확인). 공통 모듈 경계(D6)의 근거다.

## 4. 통합 방식 선택지 (D1)

| 안 | 방법 | 장점 | 단점 |
|---|---|---|---|
| A. 준현 브랜치 기준 (추천, 시안) | 준현 브랜치에서 시작하고 다른 브랜치에서 필요한 것만 옮긴다 | M1 범위(공통 모듈, 인증, Gateway, compose)가 거의 다 있다. SA #19에 맞췄다. 테스트와 CI가 있다 | 변경량이 크다. 팀 규칙·SA 변경과 SA 범위 밖 기능이 섞여 있다. Windows 문제가 있었다(시안에서 수정) |
| B. 효승 브랜치 기준 | 효승 브랜치에 인증과 나머지 서비스 모듈을 더한다 | 크기가 적당하고 테스트 31개가 인프라 없이 통과한다 | 인증 API와 reservation·payment·mock-pg가 없어 결국 준현 브랜치 인증을 옮겨 와야 한다 |
| C. 새로 조립 | dev에서 항목별로 가장 나은 구현을 하나씩 가져온다 | 항목마다 최선을 고를 수 있다 | 패키지·빌드 방식이 달라 옮길 때마다 수정이 필요하다. M1 마감(10/29)에 맞지 않는다 |

추천 근거: M1 완료 기준 중 "회원가입 → 로그인 → Gateway 인증 요청에서 `X-User-Id`·`X-User-Role` 전달"과 "새 서비스가 공통 모듈을 쓰면 응답·에러 형식이 적용된다"를 구현한 곳은 준현 브랜치뿐이다.

## 5. 논의 항목

### D2. 빌드 스크립트 DSL
Groovy는 4개 브랜치가 쓰고 익숙하다. Kotlin은 타입 검사·IDE 지원이 낫지만 1개 브랜치만 쓴다. 빌드 결과는 같고 되돌리기 쉽다. 강민 브랜치의 버전 카탈로그(`libs.versions.toml`)는 DSL과 무관해 원하면 따로 도입할 수 있다.

### D4. 패키지 이름
`iseonjwa.*`(준현), `com.iseonjwa.*`(효승·강민·영은), `com.highquality.*`(권우). SA 13장은 "(팀 확인)"으로 두었다(U12). 서비스 코드가 쌓이기 전에 정해야 바꾸는 비용이 작다. 시안은 기준 브랜치를 따라 `iseonjwa.*`다. `com.iseonjwa.*`로 정하면 통합 브랜치에서 일괄 변경한다.

### D6. 공통 모듈 경계
| 선택지 | 장점 | 단점 |
|---|---|---|
| 기준 브랜치 5개(common, -response, -web, -auth, -messaging) | 격리를 빌드 구조로 보장한다. Gateway는 `common-response`·`common-auth`만 받는다. 이미 구현·테스트됨 | 모듈이 많아 보인다 |
| 권우 ADR 0013: `common` 하나 + 웹·JPA·Kafka는 `compileOnly`, 자동 구성 없이 MVC 서비스만 스캔 | 모듈이 적다. Gateway 격리를 테스트(`GatewayEdgeApplicationTests`)로 확인했다 | 격리를 규칙과 리뷰로 지킨다. 스캔 범위를 잘못 잡으면 깨진다 |
| SA 13장 4개 | 문서와 같다 | 인증 계약(common-auth)을 둘 곳이 없다 |

추천: 기준 브랜치 구성 유지. 이미 구현된 구성이 SA 의도(Gateway에 MVC·JPA가 들어가지 않게)를 빌드 구조로 지킨다.

### D7. 테스트 인프라 방식
기준 브랜치는 인증 통합 테스트가 `compose.test.yml`로 띄운 PostgreSQL·Redis를 쓴다. 시안은 user-service 테스트 전에 `prepareTestInfra`를 자동 실행하게 해 `./gradlew build`와 IDE의 Gradle 테스트 실행만으로 통과한다(Docker 필요). 대안은 Testcontainers로 테스트마다 컨테이너를 띄우는 것이다. 어느 쪽이든 "수동 준비 없이 `build`가 통과"를 기준으로 삼는다.

### D9. 개인 브랜치 안의 팀 규칙·SA 변경
기준 브랜치는 `AGENTS.md`(대전제 10번, 작업별 문서 표), `docs/agents/quality-gates.md`, `docs/guides/writing.md`, `docs/core/SA MASTER.md`(블랙리스트 확인 open → closed, 기술 스택 줄)를 바꿨다. 시안은 이 네 파일을 dev 상태로 되돌렸고, `docs/guides/code-style.md`는 파일만 두고 문서 지도에 "팀 합의 전"으로 표시했다. 별도 PR로 리뷰한다.

주의: 코드(Gateway)는 여전히 블랙리스트 확인을 fail-closed(Redis 장애 시 503)로 구현하고 있어 SA(open)와 다르다. ADR 0005(제안) 결정에 따라 코드나 SA 중 하나를 맞춘다.

### D10. ADR·TS 번호와 중복
- ADR: 기준 브랜치 0001~0012를 살리고 권우 ADR 0001을 0013으로 옮겼다. 강민 로컬 초안 0001(공통 모듈 하나로 시작)은 철회, 0002(설정 관리 방식 비교)는 0003(native Config Server)에 보탤 예정이다.
- TS: 기준 브랜치 TS-1~19 뒤에 강민 TS-1~5를 TS-20~24, 권우 TS-1~2를 TS-25~26으로 이어 붙이고 각자 기록의 참조 번호도 바꿨다.
- Config 서버 결정자가 Notion SA v3 부록("Junhyun An 요청")과 결정 기록 DB 수정 내역("GGangMY 판단")에서 다르다. 한 번 맞춘다.

### D11. SA 범위 밖 기능
로그인 5회 실패 잠금, 관리자 비밀번호 초기화, 임시 비밀번호 변경 강제(ADR 0007, 제안), `@RequireRole` 인터셉터, `/internal/**` 외부 차단, workspace-tools, system-tests. 유지 비용과 M1 일정을 보고 채택·보류를 정한다.

## 6. 다른 브랜치에서 가져온 것

| 출처 | 가져온 것 | 상태 |
|---|---|---|
| 권우 | `.gitattributes` (+ `*.sh` LF 추가) | 반영 |
| 권우 | ADR 0001 → 0013, TS-1~2 → TS-25~26, 학습·판단 폴더 | 반영 |
| 강민 | `ConfigRepoTest`(프로필 우선순위, loadtest 샘플링, 비밀값 자리표시자 검사) + config-repo를 테스트 입력으로 등록 | 반영. 기준 브랜치는 config-server 테스트가 0개였다 |
| 강민 | TS-1~5 → TS-20~24, 학습·판단 폴더(커밋 전 최신본 포함) | 반영 |
| 영은 | reservation 하위 모듈 4개(concert·queue·reservation·lottery) × 4계층 패키지 | 반영 |
| 영은·효승 | 학습·판단 폴더 | 반영 |
| 효승 | 응답 형식 검사 테스트 | 미반영. 클래스가 달라 옮기려면 다시 써야 한다. 기준 브랜치에 `CommonWebTest`가 있다 |
| 효승 | 이미지 버전 고정 | 불필요. 기준 브랜치가 다이제스트로 이미 고정했다(분석 초안의 "latest 다수"는 오판이었다) |
| 강민 | compose 메모리 상한, 관리 UI 127.0.0.1 바인딩 | 불필요. 기준 브랜치에 이미 있다 |

## 7. 남은 작업

| 단계 | 작업 | 담당 |
|---|---|---|
| 1 | 회의에서 D1~D11 결정, 결정이 시안과 다르면 해당 커밋 수정 | 팀 |
| 2 | D4가 `com.iseonjwa.*`로 정해지면 패키지 일괄 변경 | 통합 담당 |
| 3 | D9 팀 문서 변경을 별도 PR로 리뷰 | 준현 + 팀 |
| 4 | 권우 16:06 커밋의 공통 응답 구현과 기준 브랜치 응답 형식 비교 | 권우 |
| 5 | 통합 브랜치 → dev PR, CI 통과 후 머지. 이후 서비스 브랜치는 여기서 시작 | 팀 |

## 8. 통합 시안 결과 (`ggangmy/chore/integration`)

기준 브랜치(`45874b7`) 위에 11개 커밋을 쌓았다. 기준 브랜치 대비 68 파일, +2,182 / -112.

| 커밋 | 내용 | 관련 |
|---|---|---|
| `704f7d6` docs(docs): 팀 규칙·SA 변경은 통합에서 빼고 별도 PR로 분리 | AGENTS.md, quality-gates, writing, SA MASTER를 dev 상태로 | D9 |
| `d4a9106` chore(infra): 셸 스크립트를 LF로 고정하는 .gitattributes 추가 | `*.sh`, `gradlew` LF, `*.bat` CRLF | D8 |
| `2f470d7` fix(workspace): Windows에서 POSIX 권한 없이 .env와 로그 파일을 만든다 | `./gradlew initLocalEnv`가 Windows에서 `UnsupportedOperationException`으로 `.env`를 못 만들던 문제 | D8 |
| `311c97d` chore(infra): Gradle 래퍼를 9.8.2로 맞춤 | 공식 SHA-256 체크섬 포함 | D3 |
| `a261107` chore(user): 테스트 전에 테스트 인프라를 띄우게 연결 | `build`만으로 인증 통합 테스트 통과 | D7 |
| `4fa483c` test(config): config-repo 프로필 우선순위와 비밀값 기재 검사 추가 | 강민 브랜치에서 옮김 | 6절 |
| `863eec0` chore(reservation): 하위 모듈 4개의 4계층 패키지 추가 | 영은 브랜치에서 옮김 | 6절 |
| `3f706a9` docs(infra): 강민·권우 브랜치 문제 해결 기록을 TS-20~26으로 이어 붙임 | | D10 |
| `6f9f92c` docs(docs): 권우 공통 모듈 제안을 ADR 0013으로 추가 | | D6, D10 |
| `ddfa089` docs(agents): 팀원별 학습·판단 기록 폴더와 색인 합치기 | 효승·영은·권우·강민 폴더와 두 색인 | 6절 |
| `3f19676` fix(ci): Windows에서 기록 검사가 WSL bash 대신 Git Bash로 실행되게 수정 | Gradle이 `bash`로 System32의 WSL bash를 잡아 `checkRecords`가 실패하던 문제 | D8 |

### 검증 (Windows 11, 직접 실행)
| 검사 | 결과 |
|---|---|
| `./gradlew build` (테스트 인프라를 따로 띄우지 않음) | 성공, 124개 통과 (설정 검사 추가 전) |
| `./gradlew verifyWorkspace --rerun-tasks` (테스트 인프라, 포트 노출, 기록, diff 검사 + 전체 빌드) | 성공, 127개 통과, 실패 0 |
| `./gradlew initLocalEnv` | 성공(`.env` 생성, 커밋 대상 아님) |
| 설정 검사 변이 테스트 | config-repo에 비밀번호를 직접 쓰거나 loadtest 샘플링을 바꾸면 `--rerun` 없이도 실패 |
| `records-check` | OK (근거 미기재 경고 3건은 판단자가 채울 칸) |
| `docker compose -f compose.yml -f compose.local.yml up -d --build --wait` | 컨테이너 16개 기동(앱 7개 healthy) |
| `./gradlew :system-tests:systemTest` (M1 흐름) | 13개 중 11개 통과, 2개 건너뜀(Redis 장애·재기동 시나리오는 `-PredisFailure=true`일 때만 실행). 통과한 것: 가입·로그인 응답 계약, 위조 사용자 헤더 제거와 검증한 신원 전달, 인증 누락·관리자 경로 변형 거절, JWT 종류 구분·Refresh 회전·로그아웃, Eureka 등록, Kafka 경유 Reservation → Payment 소비, 관리 엔드포인트 비공개, Config 응답 비밀값 비노출, 메트릭·Gateway → User 추적 연결 |

M1 완료 기준(`docs/core/milestones.md`) 대비: compose 한 번으로 인프라·Config·Gateway·Eureka·User 기동, 가입 → 로그인 → `X-User-Id`·`X-User-Role` 전달, Prometheus·Zipkin 연결은 위 시스템 테스트로 확인했다. 남은 기준은 "통합 브랜치가 main(dev)에 머지"다.

## 9. 확인한 것과 못 한 것

확인한 것(위 표 외):
- 권우 16:06 커밋(`6099469`): 빌드 성공, 테스트 14개 통과(Windows, 인프라 꺼진 상태)

알아 둘 것:
- 모든 브랜치의 compose 프로젝트 이름이 `iseonjwa`라서, 한 PC에서 다른 브랜치의 compose를 띄우면 같은 이름의 컨테이너가 다시 만들어진다. 볼륨 이름은 브랜치마다 달라 데이터가 섞이지는 않았다.

확인 못 한 것:
- Linux·macOS에서의 결과(CI 설정은 있으나 이 시안은 push 전이라 CI가 돌지 않았다)
- Redis 장애·재기동 시스템 테스트(`-PredisFailure=true`)
- 각 브랜치 코드의 세부 품질(이 문서는 구조·빌드·충돌 위주)
