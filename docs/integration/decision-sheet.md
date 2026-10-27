# 초기 환경 통합 결정 시트

- 회의: 2026-10-27 초기 환경 브랜치 통합 (전원)
- 근거 문서: [`branch-merge-analysis.md`](branch-merge-analysis.md) (브랜치별 빌드·테스트, 충돌 계산, 통합 시안 검증)
- 통합 시안: 로컬 브랜치 `ggangmy/chore/integration`(회의 뒤 `chore/ISE-1-integration`으로 이름 변경) (준현 브랜치 + 13커밋). Windows에서 `verifyWorkspace` 통과(테스트 127개), compose 시스템 테스트로 M1 흐름 확인
- 쓰는 법: 항목마다 각자 의견을 한 줄 적고, 회의에서 "결정" 칸을 채운다. "추천"은 분석 의견이며 결정이 아니다. 결정이 시안과 다르면 통합 담당이 해당 커밋을 고친다.

## 회의 결과 (2026-10-27)

회의에서 채운 결정과 통합 브랜치 반영 상태다. 재확인이 필요한 네 가지는 회의 뒤 통합 담당(이강민)이 확인했다(표의 "확인" 표시). 아래 "요약" 이하 절은 회의 전 시트 그대로 둔다.

| 번호 | 결정 | 통합 브랜치 반영 |
|---|---|---|
| D1 | 준현 브랜치 기준 | 시안 그대로 |
| D2 | Groovy | 시안 그대로 |
| D3 | Gradle 9.8.2 | 시안 그대로 |
| D4 | `com.iseonjwa.*` | 전 모듈 패키지 변경, [ADR 0014](../decisions/0014-package-name-com-iseonjwa.md) |
| D5 | WebFlux | 시안 그대로 |
| D6 | `common` 하나로 묶고 안에서 패키지로 나눔. 확인: 메시징도 `common`에 둔다 | 5개 모듈을 `common` 하나로 합침. 웹·JPA·Kafka·JWT는 `compileOnly`, `GatewayClasspathTest` 추가. [ADR 0013](../decisions/0013-single-common-module.md) 채택 |
| D7 | `build`만으로 통과 | 시안 그대로 |
| D8 | Windows 수정 유지 | 시안 그대로 |
| D9 | 개인 브랜치의 팀 규칙·SA 변경은 별도 PR로 분리해 리뷰 | 시안 그대로(되돌린 상태). 확인: 회의에서 정한 문서 변경(D13·D14·D16)은 통합 PR에 포함 |
| D10 | 전부 통합 | 시안 그대로 |
| D11 | 6개 기능 모두 채택 | ADR 0007 채택으로 변경 |
| D12 | PostgreSQL 17 | 시안 그대로 |
| D13 | Lombok 사용. 확인: 제한 없이 허용, 기존 코드는 바꾸지 않음 | 루트 `build.gradle`에 추가, `docs/guides/code-style.md` 8절 |
| D14 | closed, SA도 수정. closed 때문에 생기는 문제는 추가 수정 | ADR 0005 채택, SA 사본 9-5 수정 |
| D15 | Config 서버 사용 | SA 사본 기술 스택 줄 수정. 회의 질문("Config 서버 죽으면 다 죽나")은 측정해 [학습 노트](../learning/kangmin/qna/2026-10-27-config-server-role-tradeoffs.md)에 정리. 결정자 기록 불일치는 미해결 |
| D16 | 이름 `{type}/ISE-{번호}-{주제}`, dev에서 출발, squash 기본·필요하면 merge, 필수 리뷰 없음(AI 검증 후 머지·통보, 도움받은 작업만 리뷰 요청), main·dev 직접 push 금지, 마일스톤마다 main 반영. 확인: 통합 브랜치는 `chore/ISE-1-integration` | `docs/guides/git-workflow.md` 5절. GitHub 브랜치 보호 설정은 미반영 |
| D17 | 통합 담당 이강민, 리뷰 없음, 마감 M1(10/29) | — |

## 요약

| 번호 | 쟁점 | 추천 | 시안 상태 | 결정 |
|---|---|---|---|---|
| **A. 통합 방식 (먼저 결정)** | | | | |
| D1 | 통합 기준 브랜치 | 준현 브랜치 | 반영 | |
| D2 | 빌드 스크립트 DSL | Groovy | 반영 | |
| D4 | 패키지 이름 | 하나로 확정 (`iseonjwa.*` 또는 `com.iseonjwa.*`) | `iseonjwa.*` | |
| D6 | 공통 모듈 경계 | 5개 모듈 유지 | 반영 (권우 ADR 0013은 대안) | |
| D9 | 개인 브랜치 안의 팀 규칙·SA 변경 | 별도 PR로 분리 | 분리 | |
| **B. 기술·실행 환경** | | | | |
| D3 | Gradle 버전 | 9.8.2 | 반영 | |
| D5 | Gateway 방식 | WebFlux (5명 모두 같음) | 반영 | 확인만 |
| D7 | 테스트 인프라 방식 | `build`만으로 통과 | 반영 (compose.test 자동 기동) | |
| D8 | Windows 호환 수정 3건 | 유지 | 반영 | 확인만 |
| D12 | PostgreSQL 버전 | 17 (기준 브랜치) | 17 | |
| D13 | Lombok 사용 | 팀 규칙으로 정함 | 미사용 | |
| **C. 정책·범위** | | | | |
| D14 | 로그아웃 블랙리스트 Redis 장애 시 정책 | 팀 결정 필요 (SA open / 코드 closed) | 코드 closed, SA open | |
| D11 | SA 범위 밖 기능 채택 | 항목별 결정 | 코드에 있음 | |
| D15 | 결정 기록 #30(Config 서버) 확정 | 잠정 → 결정, 결정자 기록 정리 | — | |
| **D. 진행 규칙** | | | | |
| D16 | 브랜치 전략·병합 방식 | 이번에 정함 | — | |
| D17 | 최종 통합 담당과 순서 | 담당 1명 + 리뷰 1명 | — | |
| D10 | ADR·TS 번호 | 기준 브랜치 번호 유지, 나머지 이어 붙임 | 반영 | 확인만 |

---

## A. 통합 방식

### D1. 어느 브랜치를 기준으로 합치나
- 선택지: (a) 준현 브랜치 기준 + 다른 브랜치에서 골라 옮기기 (b) 효승 브랜치 기준 (c) dev에서 항목별 새 조립
- 추천: (a). M1 완료 기준 중 "가입 → 로그인 → Gateway 사용자 헤더 전달"과 "공통 응답 형식 적용"을 구현한 곳이 준현 브랜치뿐이다. 시안에서 compose 시스템 테스트로 이 흐름을 확인했다.
- 감수할 점: 변경량이 크다(184 파일). SA 범위 밖 기능이 함께 들어온다(D11).
- 결정되면: 시안을 최종 통합 브랜치로 쓰거나, 담당자가 같은 순서로 다시 만든다(D17).
- 의견: junhyun ___ / hyoseung ___ / youngeun ___ / kwonwoo ___ / kangmin (a)
- 결정: ___

### D2. 빌드 스크립트 DSL
- 선택지: (a) Groovy (b) Kotlin
- 추천: (a). 5개 중 4개 브랜치와 기준 브랜치가 Groovy다. 빌드 결과는 같고 되돌리기 쉽다.
- 감수할 점: 타입 검사·자동 완성이 약하다. 강민 브랜치의 컨벤션 플러그인·버전 카탈로그는 버린다(버전 카탈로그는 Groovy에서도 쓸 수 있어 원하면 따로 도입).
- 주의: 두 방식이 섞이면 `settings.gradle`이 우선해 모듈이 조용히 빠지고 빌드는 성공한다.
- 의견: junhyun ___ / hyoseung ___ / youngeun ___ / kwonwoo ___ / kangmin (a)
- 결정: ___

### D4. 패키지 이름
- 선택지: (a) `iseonjwa.*` (준현) (b) `com.iseonjwa.*` (효승·강민·영은) (c) `com.highquality.*` (권우)
- 추천: 지금 하나로 확정한다. 서비스 코드가 쌓인 뒤 바꾸면 전 서비스를 고쳐야 한다. (a)는 바꿀 것이 없고, (b)는 자바 관례(도메인 역순)에 가깝다. 실제 도메인이 없어 어느 쪽도 기술적 문제는 없다.
- 결정되면: (b)면 통합 브랜치에서 일괄 변경(패키지·`@SpringBootApplication` 위치·테스트). SA 13장 U12 해소.
- 의견: junhyun ___ / hyoseung ___ / youngeun ___ / kwonwoo ___ / kangmin ___
- 결정: ___

### D6. 공통 모듈 경계
- 선택지: (a) 기준 브랜치 5개(`common`, `common-response`, `common-web`, `common-auth`, `common-messaging`) (b) 권우 ADR 0013: `common` 하나 + 웹·JPA·Kafka는 `compileOnly`, MVC 서비스만 스캔 (c) SA 13장 4개
- 추천: (a). Gateway(WebFlux)는 MVC를 받으면 `MvcFoundOnClasspathException`, JPA를 받으면 DataSource 실패로 뜨지 않는다(실험으로 확인). (a)는 이 격리를 빌드 구조로 보장하고 이미 구현·테스트됐다.
- (b)의 장점: 모듈이 적다. 격리를 테스트로 확인했다. 단점: 격리를 스캔 범위 규칙과 리뷰로 지킨다.
- 결정되면: (b)면 권우 브랜치 구현과 기준 브랜치 공통 코드를 다시 맞춘다. 어느 쪽이든 SA 13장을 갱신한다.
- 의견: junhyun ___ / hyoseung ___ / youngeun ___ / kwonwoo ___ / kangmin (a)
- 결정: ___

### D9. 개인 브랜치 안의 팀 규칙·SA 변경
- 대상: `AGENTS.md` 대전제 10번(코드 읽기 흐름·`Note:` 주석 기준)과 작업별 문서 표, `docs/agents/quality-gates.md`, `docs/guides/writing.md`, `docs/core/SA MASTER.md`(블랙리스트 정책, 기술 스택 줄). `docs/guides/code-style.md`는 파일만 남김.
- 선택지: (a) 코드 통합과 분리해 별도 PR로 리뷰 (b) 통합에 그대로 포함
- 추천: (a). 팀 규칙은 코드 리뷰와 섞이면 논의가 묻힌다. 시안은 네 파일을 dev 상태로 되돌렸다.
- 결정되면: 준현이 별도 PR을 열고 팀이 리뷰한다.
- 의견: junhyun ___ / hyoseung ___ / youngeun ___ / kwonwoo ___ / kangmin (a)
- 결정: ___

## B. 기술·실행 환경

### D3. Gradle 버전
- 추천: 9.8.2 (기준 브랜치만 9.1.0). 시안에서 공식 체크섬과 함께 올렸고 전체 검사가 통과했다.
- 의견: junhyun ___ / 그 외 확인 ___
- 결정: ___

### D5. Gateway 방식 (확인만)
- 5명 모두 WebFlux다(권우는 16:06 커밋에서 전환). SA 11-2 전제와 같다.
- 결정: ___

### D7. 테스트 인프라 방식
- 현황: 인증 통합 테스트가 실제 PostgreSQL·Redis(`compose.test.yml`, 127.0.0.1:25433·26379)를 쓴다.
- 선택지: (a) 시안처럼 user-service 테스트 전에 테스트용 compose를 자동 기동 (b) Testcontainers로 테스트가 직접 컨테이너를 띄움 (c) 통합 테스트를 별도 태스크로 분리해 `build`는 단위 테스트만
- 추천: (a). 기준 브랜치 구조를 그대로 두고 `./gradlew build`와 IntelliJ Gradle 실행만으로 통과한다.
- 감수할 점: `build`에 Docker가 필요하다. 테스트 컨테이너가 끝난 뒤에도 남는다(`docker compose -f compose.test.yml down -v`로 정리).
- 의견: junhyun ___ / hyoseung ___ / youngeun ___ / kwonwoo ___ / kangmin (a)
- 결정: ___

### D8. Windows 호환 수정 (확인만)
- 시안에서 고친 것: `initLocalEnv`가 POSIX 권한 지정으로 실패, `.sh` CRLF 체크아웃(`.gitattributes` 추가), Gradle이 WSL bash를 잡아 기록 검사 실패.
- 확인할 것: 팀원 PC 운영체제. Windows 사용자는 통합 브랜치에서 `./gradlew initLocalEnv`와 `verifyWorkspace`를 한 번씩 돌려 본다.
- 결정: ___

### D12. PostgreSQL 버전
- 현황: 준현 17, 효승 17.6, 강민 18.6. SA는 버전을 정하지 않았다.
- 추천: 기준 브랜치의 17을 유지한다. 18로 올리면 데이터 경로(`/var/lib/postgresql/18/docker`)와 볼륨 마운트가 바뀌어 compose를 함께 고쳐야 한다.
- 의견: ___
- 결정: ___

### D13. Lombok 사용
- 현황: 권우 브랜치만 Lombok을 쓴다. 기준 브랜치는 쓰지 않고 record와 명시적 생성자를 쓴다.
- 선택지: (a) 쓰지 않음 (b) 쓰되 허용 범위를 정함(예: `@Getter`, `@RequiredArgsConstructor`만, 엔티티에 `@Data`·`@Setter` 금지)
- 추천: 팀 코딩 규칙으로 정한다. 정하지 않으면 서비스마다 섞인다.
- 의견: junhyun ___ / hyoseung ___ / youngeun ___ / kwonwoo ___ / kangmin ___
- 결정: ___

## C. 정책·범위

### D14. 로그아웃 블랙리스트를 확인할 Redis가 장애일 때
- 현황: SA 9-5는 open(통과시킴, 로그아웃된 토큰이 최대 30분 통함). 기준 브랜치 코드는 closed(503 + `Retry-After`, ADR 0005 제안). 시안은 SA 변경을 되돌렸지만 코드는 closed 그대로라 둘이 다르다.
- 선택지: (a) closed: 보안 우선, Redis 장애 시 로그인한 사용자 전체가 503 (b) open: 가용성 우선, 결제는 별도 결제 토큰이 있어 피해가 제한된다
- 추천: 팀 결정 사항이라 추천하지 않는다. 장애 주입 시연(M3)과 연결되므로 어느 쪽이든 이유를 기록한다.
- 결정되면: (a)면 SA 9-5 수정 PR, (b)면 Gateway 코드와 시스템 테스트(`Redis_장애는_503으로_구분...`) 수정. ADR 0005 상태 확정.
- 의견: junhyun ___ / hyoseung ___ / youngeun ___ / kwonwoo ___ / kangmin ___
- 결정: ___

### D11. SA 범위 밖 기능 채택
항목마다 채택 / 보류(코드는 두고 SA 반영 안 함) / 제거를 정한다.

| 기능 | 근거 문서 | 의견 | 결정 |
|---|---|---|---|
| 로그인 5회 실패 잠금 | ADR 0007 (제안) | | |
| 관리자 비밀번호 초기화, 임시 비밀번호 변경 강제 | ADR 0007 (제안) | | |
| `@RequireRole` 인터셉터 | 기준 브랜치 커밋 | | |
| `/internal/**` 외부 요청 404 차단 | 기준 브랜치 커밋 | | |
| workspace-tools(Java로 만든 로컬 실행·환경 초기화) | ADR 0012 | | |
| system-tests(compose 시스템 테스트) | ADR 0011·0012 | | |

- 추천: system-tests는 유지(M1 흐름을 한 번에 확인하는 유일한 수단). 나머지는 유지 비용과 M1 일정을 보고 정한다.

### D15. 결정 기록 #30(기술 스택·Config 서버) 확정
- 현황: Notion 결정 기록 #30은 잠정 결정. Config 서버는 5개 브랜치 중 4개가 구현했다(권우 제외).
- 확인할 것: Config 서버 결정자가 SA v3 부록("Junhyun An 요청")과 결정 기록 DB 수정 내역("GGangMY 판단")에서 다르다. 사실을 맞춘다. Java 21 선택 이유 칸이 비어 있다.
- 추천: Config 서버(native) 유지로 확정. 되돌릴 조건은 "기동 실패·설정 추적 문제가 반복되면 공유 폴더 + `spring.config.name` 방식"으로 바꾼다(실험으로 같은 우선순위 확인).
- 의견: ___
- 결정: ___

## D. 진행 규칙

### D16. 브랜치 전략과 병합 방식
`docs/guides/git-workflow.md`는 "브랜치 전략은 팀과 정한 뒤에 추가한다"로 비어 있다. 병렬 작업 전에 정한다.

| 항목 | 선택지 | 추천 | 결정 |
|---|---|---|---|
| 브랜치 이름 | (a) `{type}/ISE-{번호}-{주제}` (b) Linear 기본 형식 (c) `{이름}/{type}/{주제}` | (a). Linear 이슈와 자동 연결되고 사람 이름 없이도 담당이 이슈에 있다 | |
| 작업 브랜치의 출발점 | dev | dev | |
| 병합 방식 | merge commit / squash / rebase | squash. 작업 브랜치의 작은 커밋을 PR 하나로 묶어 dev 이력을 읽기 쉽게 | |
| 리뷰 | 승인 1명 이상 / 없음 | 1명 이상 + CI(`verifyWorkspace`) 통과 | |
| 보호 규칙 | main·dev 직접 push 금지 | 적용 | |
| main 반영 | 마일스톤마다 dev → main | 마일스톤마다 | |

- 결정되면: git-workflow.md 2절 앞에 브랜치 전략 절을 추가한다. GitHub 브랜치 보호 설정.

### D17. 최종 통합 담당과 순서
- 추천: 담당 1명이 통합 브랜치를 만들고 1명이 리뷰한다. 순서는 다음과 같다.
  1. D1·D2·D4·D6 결정 반영(시안과 다르면 수정)
  2. 통합 브랜치 → dev PR (팀 문서 변경 D9는 별도 PR)
  3. Windows·CI 양쪽에서 `verifyWorkspace` 통과 확인
  4. 머지 후 각자 개인 초기 환경 브랜치는 정리(삭제는 본인 확인 후)
  5. 이후 서비스 작업 브랜치는 dev에서 시작
- 담당: ___ / 리뷰: ___
- 마감: M1(10/29)

### D10. ADR·TS 번호 (확인만)
- ADR: 기준 0001~0012 유지, 권우 ADR 0001 → 0013. 강민 로컬 초안 0001은 철회, 0002(설정 관리 방식 비교)는 0003에 보탠다.
- TS: 기준 TS-1~19, 강민 TS-1~5 → TS-20~24, 권우 TS-1~2 → TS-25~26. 각자 기록의 참조 번호도 바꿨다.
- 결정: ___

---

## 회의 후 할 일 기록

| 할 일 | 담당 | 마감 |
|---|---|---|
| | | |
