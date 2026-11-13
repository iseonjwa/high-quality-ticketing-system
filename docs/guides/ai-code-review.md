# AI가 만든 코드를 검토하는 방법

이 프로젝트의 코드는 대부분 AI(Claude Code, codex)가 쓴다. 이 문서는 팀이 그 코드를 어떻게 검토하고 검증해 왔는지, 무엇을 실제로 잡았는지, 어디가 약한지를 근거와 함께 적는다. 7절은 남은 기간에 보탤 방법이다. 7절은 팀이 동의하기 전까지 제안이다.

- 정본 관계: 리뷰 규칙은 [`git-workflow.md`](git-workflow.md) 5절, 완료 기준은 [`../agents/quality-gates.md`](../agents/quality-gates.md), AI 판단 기록 절차는 [`ai-judgment` 스킬](../../.agents/skills/ai-judgment/SKILL.md)이다. 이 문서는 그 규칙들이 AI 코드 검토에서 어떻게 맞물리는지 묶는다.
- 기준 시점: 2026-11-13 `origin/dev`. 숫자는 이날 다시 셌다.

## 1. 왜 이렇게 검토하나

- **승인 대신 관문.** `git-workflow.md` 5절은 필수 승인을 두지 않는다. 작성자가 AI 검증을 거친 뒤 머지하고, CI(`build`, `records-check`)를 필수 관문으로 둔다. 다섯 명이 서비스를 하나씩 맡아, 사람 리뷰를 기다리면 그것이 병목이 되기 때문이다.
- **쓴 모델이 자기 코드를 리뷰하면 같은 곳을 놓친다.** 그래서 작성과 검토를 다른 세션, 가능하면 다른 모델에 맡긴다. 같은 계열 모델은 맹점을 공유할 수 있다([강민 학습 노트](../learning/kangmin/qna/2026-11-04-main-implement-subagent-review.md)). 그래서 검토 결과는 테스트, 소스 확인, 측정 중 하나로 다시 확인한다.
- **판단은 사람이 한다.** AI 리뷰 지적마다 수용·부분 수용·거부·보류를 사람이 고르고, 근거와 함께 `docs/ai-judgments/<이름>/`에 남긴다(AGENTS.md 대전제 3). 거부는 무게와 상관없이 개별 기록으로 쓴다.

대가도 있다. 검토는 주로 로컬 세션에서 일어나 PR 화면에 흔적이 적다. 2026-11-13 기준 머지된 PR 313개 중 GitHub 리뷰가 남은 PR은 12개다(6절).

## 2. 다섯 겹의 검토

| 겹 | 언제 | 무엇이(누가) | 주로 잡는 것 |
|---|---|---|---|
| L0 기준 고정 | 쓰기 전 | 사람이 SA·ADR·계약 문서를 정하고 AI는 그 안에서 구현 | 설계를 AI가 임의로 정하는 것, 서비스 간 계약 어긋남 |
| L1 작성 세션의 자기 검증 | 쓰는 중 | 작성 세션. 실패하는 테스트부터, 변이 확인, 실제로 띄워 보기 | 동작하지 않는 코드, 검출력 없는 테스트 |
| L2 다른 눈의 AI 리뷰 | PR 전 | 작성과 다른 세션·모델(Opus 리뷰어, codex 독립 검토, 다른 세션의 분류) | 실패 경로, 경합, 라이브러리 기본값, 계약 불일치 |
| L3 CI 자동 관문 | PR·머지 | GitHub Actions | 빌드·테스트 회귀, 비밀값, 기록 누락, 포트 노출 |
| L4 배포 환경 확인 | 머지 뒤 | 배포 롤백, Loki·Grafana·Zipkin, k6 측정, Slack 경보 | 로컬에서 안 보이는 기동 실패, 규모 문제, 경보 공백 |

### L0. 쓰기 전에 기준을 고정한다
- 설계 정본은 SA MASTER와 `docs/decisions/`(ADR)다. AI에게 일을 줄 때 해당 절과 ADR을 같이 준다. 설계와 다르게 구현해야 하면 먼저 사용자에게 묻고 ADR을 남긴다(AGENTS.md).
- 서비스 간 접점(이벤트·토큰·내부 API·공유 설정)을 바꾸면 Linear `contract` 이슈로 상대 담당에게 알린다([`contract-issues.md`](contract-issues.md)). 작업 전에 나에게 온 `contract` 이슈를 확인한다.
- 효과: 리뷰가 "이 코드가 맞나"를 판단할 기준이 생긴다. 기준이 없으면 AI 리뷰는 취향 지적이 된다.

### L1. 작성 세션이 스스로 검증한다
[`quality-gates.md`](../agents/quality-gates.md)가 정한 순서다.
- 도메인 규칙과 버그 수정은 **실패하는 테스트로 시작**한다. 고친 뒤 통과를 확인한다.
- **변이 확인**: 고친 코드를 일부러 되돌려 테스트가 실패하는지 본다. 테스트가 결함을 실제로 잡는지 확인하는 방법이다. 예: Gateway 기동 실패를 막는 컨텍스트 테스트는 `@Autowired`를 빼면 실패하는 것을 확인했다(TS-53). 계층 정리 PR #88은 변이 7건을 모두 잡았다.
- 설정·라우팅·기동이 바뀌면 **실제로 띄워 본다**. 못 했으면 "미확인"이라고 PR 결과 표에 쓴다(PR 템플릿).
- 빌드 성공을 API 동작 확인으로 대신하지 않는다.

### L2. 다른 눈의 AI가 리뷰한다
팀원마다 도구가 달라 방식도 조금씩 다르다. 기록에 남은 방식은 다음과 같다.

| 팀원 | 방식 | 근거 |
|---|---|---|
| 준현 | 구현은 옆 패널의 Sonnet 작업자, 설계·지시·리뷰는 Opus 메인. PR 전 Opus(deep-reasoner) 리뷰, 찾은 것과 반영한 것을 PR 본문에 적음. 팀원 PR을 AI로 리뷰해 PR 코멘트로 올림 | PR #280·#284·#481, [두 에이전트 독립 검토](../ai-judgments/junhyun/2026-10-26-two-agent-review-fixes.md), [효승 PR 리뷰](../ai-judgments/junhyun/2026-11-03-review-hyoseung-dlq-and-reconciliation-api.md) |
| 강민 | 구현 세션의 하위 에이전트가 미커밋 변경을 리뷰하고, 다른 세션이 지적을 코드와 대조해 "이번 PR 수정·담당자 확인·후속"으로 나눔. 담당 이슈 33개 엄격 리뷰 보고서. AI 조사 결과를 라이브러리 바이트코드(`javap`)로 다시 확인 | [ISE-150 지적 11건 분류](../ai-judgments/kangmin/2026-11-04-ise-150-review-triage.md), [추첨 MVP 코드 리뷰 보고서](../learning/kangmin/qna/2026-11-02-lottery-mvp-code-review-report.md), [기본 벌크헤드 확인](../ai-judgments/kangmin/2026-11-09-gateway-has-default-bulkhead.md) |
| 권우 | AI에게 설계 허점 점검을 시키고, 결과를 동시성 테스트와 k6 측정으로 확인. 측정 도구의 한계는 사람이 막음 | [예약 설계 리뷰](../learning/kwonwoo/qna/2026-10-28-reservation-system-design-review.md), [JUnit 근사 대신 k6](../ai-judgments/kwonwoo/2026-11-04-k6-over-junit-load-approximation.md) |
| 영은 | AI가 낸 설계 근거를 하나씩 되물어 틀린 것을 철회시킴 | [대기열 AI 근거 정정](../ai-judgments/youngeun/2026-10-29-queue-ai-reasoning-corrections.md) |
| 효승 | 본인 기록은 저장소에 없다. 효승의 PR #282·#283은 준현이 AI로 리뷰했고 효승이 수정 커밋으로 반영했다 | PR #282·#283 |

공통 원칙:
- **작성과 검토를 나눈다.** 같은 세션이 쓴 코드를 같은 세션이 "리뷰"하면 2절 L1에 그친다.
- **지적은 근거로 다시 확인한다.** 라이브러리 기본값은 소스나 바이트코드로, 경합은 테스트로, 성능은 측정으로 본다. 확인하지 못한 지적은 "코드 읽기 판단, 재현하지 않음"이라고 적는다(예: 효승 PR 리뷰 기록의 검증 절).
- **지적을 분류한다.** 이번 PR에서 고칠 것, 담당자가 정할 것, 후속 이슈, 거부로 나눈다. 다른 담당 영역의 지적은 그 담당에게 넘긴다.

### L3. CI가 자동으로 막는다
| 검사 | 하는 일 | 위치 |
|---|---|---|
| `build`(필수) | `./gradlew verifyWorkspace`: 격리된 테스트 PostgreSQL·Redis를 띄우고(`prepareTestInfra`), 포트 노출 정책(`checkPortExposure`), 기록 검사(`checkRecords`), `git diff --check`, 모든 모듈의 build(컴파일·JUnit)를 돈다. `frontend/`만 바뀌면 Java 단계를 건너뛴다 | `.github/workflows/build.yml`, `build.gradle` |
| `records-check`(필수) | 기록 구조(사람별 폴더, 색인, 필수 머리말, 공통 판단 표)와 비밀값 흔적(`ghp_`, `github_pat_`, `sk-`, `AKIA…`, Slack 토큰, 개인 키)을 검사한다. 같은 검사가 Claude Code `Stop` 훅으로도 돈다 | `script/records-check.sh`, `.claude/settings.json` |
| `prometheus-rules`, `loki-rules` | 경보·로그 규칙 문법과 단위 테스트 | `script/check-prometheus-rules.sh`, `script/check-loki-rules.sh` |
| `frontend` | 포맷, 린트, 테스트, 빌드 | `.github/workflows/frontend.yml` |
| `images` | main 머지 때 서비스 이미지를 GHCR에 올린다 | `.github/workflows/images.yml` |

테스트 규모: Java `@Test` 1,833개(`@ParameterizedTest` 52개 별도), 테스트 파일 287개, 시스템 테스트 16개, 프론트 테스트 파일 101개. 결제 쪽에는 PG·예매 조회 계약 테스트 5개가 있다.

### L4. 배포 환경에서 다시 확인한다
- 배포 스크립트는 새 이미지가 뜨지 않으면 직전 커밋으로 되돌린다. 로그는 Loki, 지표는 Prometheus·Grafana, 추적은 Zipkin, 경보는 Slack으로 온다.
- 배포 서버에서 k6로 부하를 걸어 로컬에서 보이지 않는 문제를 찾는다([`load-test.md`](../design/infra/load-test.md)).
- L3까지 통과한 코드가 여기서 걸린 사례가 4절의 5~7번이다. CI 초록이 곧 운영 정상이라는 뜻이 아님을 보여 준다.

## 3. 리뷰 지적을 판단하는 기준

| 판단 | 언제 | 남기는 것 |
|---|---|---|
| 수용 | 지적이 근거(재현, 소스, 측정)로 확인됐고 범위 안 | 고친 커밋과 그 결함을 잡는 테스트 |
| 부분 수용 | 문제는 맞지만 제안한 고침이 과하거나 범위가 넓음 | 받은 부분과 뺀 부분, 뺀 이유 |
| 거부 | 근거가 틀렸거나, 측정 오차 안이거나, 형식만 늘거나, 운영에서 나오지 않을 조건을 전제함 | 개별 판단 기록(거부는 항상 개별 기록) |
| 보류 | 다른 담당의 결정이 필요하거나 측정이 필요함 | 담당자·이슈 링크 |

거부한 실제 근거의 유형(5절 사례에서):
- 측정 차이가 같은 값을 반복 측정한 오차 안이다(동시 처리 상한 128 제안).
- 판정할 수 없는 상태를 전제한다. 타임아웃은 실패가 아니라 "모름"이다(결제 재시도 이어받기 제안).
- 형식만 늘고 규칙 위반을 고치지 않는다(응답 레코드 분리 제안).
- 측정 숫자만 좋게 하고 운영과 다른 조건을 만든다(측정용 차단기 완화 제안).

## 4. 실제로 잡은 결함

| # | 무엇이 잘못됐나 | 잡은 겹 | 고친 곳과 검증 |
|---|---|---|---|
| 1 | DB가 멈추면 연결 생존 검사가 5초를 기다려 Gateway 3초 시한을 넘김. HikariCP 생존 검사 시한 기본값은 `connection-timeout`(1초)으로 줄지 않는다 | L2 Opus 리뷰가 HikariCP·pgjdbc 소스를 읽고 찾음. 메인이 소스와 변이 확인으로 재현 | PR #280. DB `docker pause` 재확인 3회 모두 2.0초에 503 |
| 2 | 예매 대사 RC-1~RC-6에 결함 5가지(RC-2 미탐, RC-5·RC-6 영구 OPEN, 빈 응답 신뢰, 커서 무한 루프, 직접 주소 실패). Sonnet 작업자가 구현 | L2 Opus 리뷰 | PR #284. 반영 뒤 `:payment-service:test` 758개 통과. RC-5·RC-6은 ADR이 바뀌는 일이라 사용자가 선택지를 고름 |
| 3 | 예매 쪽 응답(`SuccessResponse.data` 배열)과 결제 쪽 어댑터(`{"items": …}` 전제)가 어긋나 이으면 매 회차 조회 실패 | L2 두 PR의 diff를 대조한 AI 리뷰 | PR #289에서 어댑터를 공통 응답 형식에 맞춤 |
| 4 | DLQ 처리기가 DB 연결 실패를 업무 실패로 분류, NUL 문자·긴 키로 저장 실패 시 파티션 정지, 먼저 OPEN으로 남은 건이 닫히지 않음 | L2 팀원 PR에 대한 AI 리뷰 | 효승 수정 커밋 `3987eb6`, 테스트 5개 → 9개 |
| 5 | 두 PR이 같은 Flyway 버전 `V5`를 써서 머지 뒤 dev에서 결제 서비스가 안 뜸(파일 이름이 달라 충돌로 안 보임) | L3 머지 뒤 dev `build` 실패(268건) | ISE-155에서 `V6`으로 옮김(TS-31) |
| 6 | Gateway 생성자에 `@Autowired`가 없어 배포 서버에서 기동 실패. 단위 테스트는 `new`로 만들어 통과 | L4 배포 롤백, Loki 로그 | 컨텍스트 테스트 추가, `@Autowired`를 빼면 실패 확인(TS-53) |
| 7 | 경보 규칙 단위 테스트는 통과했지만 Micrometer 태그 `job`이 수집 라벨과 겹쳐 실제로는 경보가 울릴 수 없었음 | L4 실제 Prometheus 조회 0건 | 태그를 `task`로, 테스트 입력을 실제 수집 형태로, `job`·`instance` 태그를 막는 테스트(TS-37) |
| 8 | 예매 대사가 배포 규모(변경 약 14만 6천 건)에서 쪽 상한을 넘어 매 회차 실패. 로컬은 몇 건으로만 확인 | L4 Slack 경보, 배포 서버 로그 | 워터마크 이후만 읽는 방식(TS-62, PR #552) |
| 9 | 같은 사용자가 탭 4개로 동시에 선점하면 한도 4매를 넘어 8매. 멱등키 기록이 늦어 자기 요청끼리 409 | L1 동시성 테스트 C3~C6 | C4 30라운드 모두 4매 이하, C5 자기 409 0건(TS-65~68) |
| 10 | 관리자 경로를 루트·matrix parameter·인코딩으로 바꾸면 권한 검사를 통과 | L2 codex 두 에이전트 독립 검토(인증·보안, 워크스페이스·인프라를 나눠 봄) | 라우터와 같은 경로 해석으로 검사, 수정 전 실패하던 테스트가 수정 뒤 통과(TS-9, [기록](../ai-judgments/junhyun/2026-10-26-two-agent-review-fixes.md)) |

1~4번은 리뷰 단계에서 머지 전에(1번은 머지된 #278의 후속 PR에서) 고쳤다. 5~8번은 L3·L4가 아니었으면 운영에서 드러났을 결함이다. 9번은 같은 결함을 k6 측정은 드러내지 못했고 동시성 테스트가 잡았다.

## 5. 근거를 들어 거부한 AI 제안

| 제안 | 거부 근거 | 기록 |
|---|---|---|
| 결제 커밋 응답 유실 때 재시도가 기존 결제를 이어받아 승인 | PG를 불렀는지 판정할 기록이 없어 이중 승인이 날 수 있다. 호출 직전 표시도 같은 방식으로 유실된다 | [junhyun 11/9](../ai-judgments/junhyun/2026-11-09-ise169-reject-retry-takeover.md), ADR 0048 |
| Gateway 동시 처리 상한을 64에서 128로 | 굿풋 차이 1.5%가 반복 측정 차이 5% 안이고, p95는 128이 약 두 배(162ms 대 87ms) | [kangmin 11/11](../ai-judgments/kangmin/2026-11-11-keep-in-flight-limit-64.md) |
| "Gateway에는 벌크헤드가 없다"는 AI 조사 결과 | 설정에 없으면 기본값이라는 반문에서 시작해 바이트코드로 기본 벌크헤드(동시 25)를 확인 | [kangmin 11/9](../ai-judgments/kangmin/2026-11-09-gateway-has-default-bulkhead.md), TS-56 |
| 대기열 설계 근거(멱등이면 같은 응답, issued 선기록이면 자동 복구) | 멱등은 응답이 아니라 서버 상태에 대한 효과가 같다는 뜻이고, 토큰 만료 뒤에는 틈이 생긴다 | [youngeun 10/29](../ai-judgments/youngeun/2026-10-29-queue-ai-reasoning-corrections.md), ADR 0031 |
| 측정을 위해 차단기 기준을 완화 | 운영에서는 나오지 않을 숫자가 나온다. Gateway는 다른 담당 영역이다 | [youngeun 11/10](../ai-judgments/youngeun/2026-11-10-no-breaker-relax-for-measurement.md) |
| 같은 JVM 안의 JUnit으로 부하를 근사 | Gateway를 거치지 않아 실제 경로가 아니다. k6로 다시 재니 결론이 달랐다 | [kwonwoo 11/4](../ai-judgments/kwonwoo/2026-11-04-k6-over-junit-load-approximation.md) |

공통 판단 표 기준 전체 판단 170건 중 거부 55건, 부분 수용 31건이다([`ai-judgments/README.md`](../ai-judgments/README.md)). 이 숫자는 코드 리뷰만이 아니라 AI의 모든 제안에 대한 판단을 합친 것이다.

## 6. 한계

- **PR에 흔적이 적다.** GitHub 리뷰가 남은 PR은 12개다(#57, #88, #91, #94, #95, #194, #231, #282, #283, #321, #505, #526). 검토 결과는 PR 본문, 판단 기록, 트러블슈팅에 흩어져 있다.
- **AI 리뷰의 효과를 재지 않았다.** 리뷰가 결함을 몇 개 줄였는지는 측정하지 않았다. 4절은 찾은 사례이지 비율이 아니다.
- **같은 계열 모델의 맹점.** Claude 계열끼리 리뷰하면 같은 곳을 놓칠 수 있다. codex가 나오는 판단 기록 16건은 10/23~11/2(SA 검토, 초기 구현 검토)에 몰려 있고, 11월 구현 리뷰는 대부분 Claude 계열끼리 했다.
- **자동 구조 검사가 없다.** 4계층 의존 방향은 AI 점검([`sa-review/06`](../sa-review/06-layered-architecture-check.md))과 사람 판단으로 지켰고, ArchUnit 같은 자동 검사는 없다.
- **테스트 인프라.** Testcontainers 대신 `compose.test.yml`의 격리된 PostgreSQL·Redis를 쓴다. 변이 테스트 도구(pitest 등)는 없고 변이 확인은 손으로 한다.
- **팀원마다 기록의 양이 다르다.** 효승의 검토 방식은 저장소 기록으로 확인할 수 없다.

## 7. 남은 기간에 보탤 것 (제안, 팀 동의 전)

### 7-1. PR마다 AI 리뷰 결과를 PR에 남긴다
작성 세션과 다른 세션에서 리뷰를 돌리고, 결과를 PR 코멘트로 올린다. 작성자는 지적마다 수용·거부로 답한다. 거부는 판단 기록 개별 기록으로 남긴다.
- **스크립트**: `script/ai-review.sh <PR 번호>`가 PR 머리 커밋을 임시 worktree로 꺼내 Claude Code(읽기 전용 도구만)로 리뷰하고 결과를 `build/ai-review/`에 쓴다. codex CLI만 쓰면 `--engine codex`(read-only 샌드박스)다. `--post`를 주면 PR 코멘트로 올린다. 프롬프트는 [`ai-code-review-prompt.md`](ai-code-review-prompt.md)다. PR #577로 시험했을 때 Claude와 codex가 짚은 곳이 달랐다(프롬프트 문서 첫 단락).
- **Claude Code 안에서**: 내장 `/code-review <PR 번호> --comment`도 인라인 코멘트로 올린다. 프로젝트 기준을 쓰려면 위 프롬프트 파일을 같이 읽게 한다.
- **Claude 채팅(웹·앱)**: 저장소를 읽지 못하므로 PR의 `.diff`(PR 주소 끝에 `.diff`)와 프롬프트 파일을 첨부한다. 방법은 [`presentation/required-deliverables.md`](../presentation/required-deliverables.md) K7이다.

### 7-2. CodeRabbit을 단다 (선택)
- 공개 저장소는 무료다(CodeRabbit 요금제 문서, 2026-11-13 확인). GitHub 조직 관리자가 앱을 설치한다.
- 모든 PR에 자동으로 달리므로 사람이 잊어도 흔적이 남는다. 대신 코멘트가 많아지고, 우리 설계 문서를 모르는 일반 리뷰다. 설정 파일(`.coderabbit.yaml`)로 한국어 리뷰와 `docs/` 제외를 정할 수 있다.
- 단다면 7-1과 같은 규칙(지적마다 수용·거부로 답함)을 쓴다.

### 7-3. 계약 경계는 사람이 본다
`contract` 이슈가 걸린 PR은 상대 담당이 PR에서 승인하거나 코멘트를 남긴다. 지금 5절 규칙("다른 사람의 작업을 도왔을 때만 리뷰 요청")을 이 경우까지 넓히는 제안이다.

## 8. 팀원 세션에서 바로 쓰는 리뷰 지시문

작성한 세션이 아닌 새 CLI 세션(Claude Code나 codex, 다른 모델이면 더 좋다)에 아래를 붙여 넣는다. `<PR 번호>`만 바꾼다. Claude 채팅만 쓰면 [`required-deliverables.md`](../presentation/required-deliverables.md) K7의 첨부 방법을 쓴다.

```text
이선좌 저장소의 PR #<PR 번호>를 리뷰해 줘. 이 PR의 코드는 AI가 썼고, 너는 작성한 세션이 아니다.
1. `gh pr view <PR 번호>`와 `gh pr diff <PR 번호>`로 목적과 변경을 읽고, 바뀐 파일의 주변 코드와 관련 설계(SA MASTER 해당 절, docs/decisions/의 ADR, docs/design/의 계약 문서)를 찾아 읽는다.
2. docs/guides/ai-code-review-prompt.md의 점검 항목과 출력 형식을 그대로 따른다.
3. 파일을 고치지 말고, 커밋·푸시·PR 코멘트도 하지 마. 결과는 이 대화에만 출력한다.
```

결과를 PR에 올릴지는 작성자가 정한다. 올릴 때는 `gh pr comment <PR 번호> --body-file <결과 파일>`을 쓰고, 지적마다 수용·거부를 답글로 단다.
