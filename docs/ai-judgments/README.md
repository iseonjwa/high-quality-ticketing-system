# AI 판단 기록

사용자가 AI(Claude Code, codex, ChatGPT 등)의 응답·제안·리뷰·코드를 받고 내린 판단을 모두 남기는 곳이다. 수용, 부분 수용, 거부, 보류, 선택지 고르기, 팀·튜터 결정 전달이 모두 대상이다. 작성 절차는 [`ai-judgment` 스킬](../../.agents/skills/ai-judgment/SKILL.md)을 따른다.

## 왜 남기나
- 발제 [필수] "AI 기반 요구사항 정의 및 도메인 모델링 검증": AI 설계 검증 로그에 검증 항목 3개 이상, 그중 1개 이상은 AI 피드백을 "거부"하고 근거를 쓴다. 계층형 아키텍처(4계층) 검증은 필수다.
- 발표 자료에는 팀당 AI 활용·검증 대표 사례 2건 이상이 들어가고, 심사 랭킹에 반영된다. 사례마다 해결하려던 작업, Claude Code 활용 방식, 채택·수정·기각 이유, 빌드·테스트·API 검증 결과, 팀이 직접 내린 MSA 설계 판단이 필요하다.
- 면접에서 "AI가 해줬다"가 아니라 "AI를 활용해 이런 판단을 내렸다"고 답하려면 판단한 순간의 근거가 남아 있어야 한다.

## 구성
- `<이름>/`: 팀원 한 사람의 판단. 폴더 이름은 팀원 영문 이름이다(`docs/learning/<이름>/`과 같다). 자기 폴더만 고친다.
  - `README.md`: 그 사람의 개별 기록 색인과 일지 목록
  - `YYYY-MM-DD-<slug>.md`: 개별 기록. 설계·범위·기술 선택처럼 설명할 만한 판단과, 거부한 판단 전부
  - `daily/YYYY-MM-DD.md`: 작은 판단을 한 줄씩 남기는 일지
- 이 파일: 사람별 폴더 목록과, 팀이 함께 알면 좋은 판단(`shared: 예`)의 중앙 표

처음 쓰는 팀원은 `<이름>/README.md`를 만들고 아래 "팀원별 기록" 표에 한 줄을 추가한다. 스킬이 판단 시점에 만들어 준다.

## 공통 판단의 기준
개별 기록 머리말의 `shared`를 `예`로 둔다. 파일은 옮기지 않고 아래 표에 링크만 추가한다.

- 서비스 경계, 범위, 기술 선택, 일정, 역할, 협업 방식처럼 다른 팀원의 작업에 영향을 주는 판단
- 팀 규칙, 문서 체계, 에이전트 지침을 바꾸는 판단
- 발표 대표 사례로 쓸 후보(`presentation: 후보`)
- 되돌리기 어려운 결정이면 [`docs/decisions/`](../decisions/README.md)에 ADR도 남기고 서로 링크한다.

내 코드, 문구, 학습 방식처럼 나에게만 해당하는 판단은 `아니오`로 둔다.

## 팀원별 기록

| 폴더 | 내용 |
|---|---|
| [hyoseung](hyoseung/README.md) | 개별 기록 2건, 일지 1일 |
| [junhyun](junhyun/README.md) | 개별 기록 17건, 일지 2일 |
| [kangmin](kangmin/README.md) | 개별 기록 18건, 일지 3일 |
| [kwonwoo](kwonwoo/README.md) | 개별 기록 5건, 일지 0일 |
| [youngeun](youngeun/README.md) | 개별 기록 4건, 일지 0일 |

## 팀 공통 판단

최신이 위로 오게 한다. 거부 판단은 발제 제출 기준(1건 이상)을 채우는지 바로 볼 수 있게 굵게 표시한다.

| 날짜 | 판단 대상 | 판단자 | AI | 판단 | 발표 후보 | 기록 |
|---|---|---|---|---|---|---|
| 2026-10-30 | 트래픽 집중 지점 격리와 향후 스케일업을 위해 좌석 선점(seathold)을 별도 도메인 모듈로 분리하되 단방향 의존 규칙 수용 (ADR 0034) | kwonwoo | Antigravity (Gemini) | 부분 수용 | 후보 | [기록](kwonwoo/2026-10-30-seathold-module-separation.md) |
| 2026-10-29 | 입장 토큰 회차 비교: 선점 경로를 /schedules/{scheduleId}/reservations로 바꾸고 Gateway가 경로로 비교 (계약 ①) | youngeun | Claude (Cowork) | 수용 | 아님 | [기록](youngeun/2026-10-29-entry-token-schedule-check-path.md) |
| 2026-10-29 | 대기열 설계 설명 중 AI 근거 두 가지(멱등성, issued 선기록의 자동 복구)를 틀렸다고 지적, 만료 기록 처리 추가 | youngeun | Claude (Cowork) | **거부** | 후보 | [기록](youngeun/2026-10-29-queue-ai-reasoning-corrections.md) |
| 2026-10-29 | 대기열: 토큰 API 분리, READY 상태, 진입·순번 조회 같은 상태 응답, 순번 조회 읽기 전용, jti 계산 (ADR 0031) | youngeun | Claude (Cowork) | 부분 수용 | 후보 | [기록](youngeun/2026-10-29-queue-token-api-ready.md) |
| 2026-10-29 | 좌석 선점 결과 계약(ISE-21): 동기형 멱등 재요청 처리 규칙(실패 시 롤백·동시 PK 충돌 재조회) 수용 및 응답에 `seatIds` 포함 결정 | kwonwoo | Antigravity (Gemini) | 수용 | 후보 | [기록](kwonwoo/2026-10-29-seat-hold-contract-and-idempotency.md) |
| 2026-10-28 | Notion SA 반영 방법: 구간 교체 HTML 대신 v3.3 전체를 가져오기로 교체 | kangmin | Claude Code | **거부** | 아님 | [기록](kangmin/2026-10-28-notion-sa-full-import.md) |
| 2026-10-28 | 테이블 이름 복수형 적용 범위: 결제 코드·문서는 후속 이슈(ISE-67), `outbox_events`, M3 테이블 제외 | kangmin | Claude Code | 수용 | 아님 | [기록](kangmin/2026-10-28-table-naming-scope.md) |
| 2026-10-28 | Notion SA 동기화: 변경마다 바로 고치는 AI 방식 대신 쌓아서 한 번에 반영 | kangmin | Claude Code | **거부** | 아님 | [기록](kangmin/2026-10-28-notion-sa-batch-sync.md) |
| 2026-10-28 | 역할 변경 확정: 결제 담당이 Mock PG까지 맡고 지원 불필요, SA·마일스톤 담당 표기 정정 | kangmin (결제 담당 확인 전달) | Claude Code | 수용 | 아님 | [기록](kangmin/2026-10-28-role-change-confirmed.md) |
| 2026-10-28 | Outbox·처리 이력 코드를 payment-service에 둔 AI 판단을 되돌려 common 모듈로 옮기기로 함 | junhyun | Claude Code, Opus 리뷰 | 수용 | 아님 | [기록](junhyun/2026-10-28-outbox-code-location.md) |
| 2026-10-27 | 결제 상세 설계 빈틈 11개 결정과 Opus 검토 반영(10/28 갱신: 예매당 결제 하나, 환불 자동 복구, Ops 명령, 대사, 불변 조건) | junhyun | Claude Code | 수용 | 후보 | [기록](junhyun/2026-10-27-payment-design-gaps.md) |
| 2026-10-27 | AI 설계 검증 #7: 계층형 아키텍처 제안 7건 부분 수용, 응답 레코드 분리 거부 | kangmin | Claude Code | 부분 수용 | 후보 | [기록](kangmin/2026-10-27-layered-architecture-check7.md) |
| 2026-10-27 | 대기열 입장 토큰 발급: 발급 기록(issued)을 남기는 B안 선택, 입장 처리 중복 방지·sub 확인 보강 | youngeun | Claude (Cowork) | 수용 | 후보 | [기록](youngeun/2026-10-27-queue-admission-guards.md) |
| 2026-10-27 | Notion SA: AI의 블록 7곳 수정 대신 v3.2 통째 재등록 | kangmin | Claude Code | **거부** | 아님 | [기록](kangmin/2026-10-27-sa-v3-2-reregister.md) |
| 2026-10-27 | 회의 결정 후속: AI가 나눈 후속 이슈 대신 이번 통합에서 끝내기로 함 | kangmin | Claude Code | **거부** | 아님 | [기록](kangmin/2026-10-27-meeting-followups-finish-now.md) |
| 2026-10-27 | 통합 회의: 결정 시트 추천 중 D6 공통 모듈(하나+패키지), 리뷰 없음, 패키지 com.iseonjwa로 정함 | 팀 (전달: kangmin) | Claude Code | 부분 수용 | 후보 | [기록](kangmin/2026-10-27-integration-meeting-decisions.md) |
| 2026-10-27 | 브랜치 통합: AI 추천(준현 브랜치 기준, Groovy, WebFlux 등 D1~D11)을 회의 입장으로 수용 | kangmin | Claude Code | 수용 | 후보 | [기록](kangmin/2026-10-27-branch-integration-recommendation.md) |
| 2026-10-27 | M1 진행 방식: AI의 "각자 만드는 건 공통 모듈만" 설명 대신 전원이 같은 범위를 각자 만들고 병합 | 팀 (전달: hyoseung) | Claude Code | **거부** | 아님 | [기록](hyoseung/2026-10-27-m1-everyone-builds-full-skeleton.md) |
| 2026-10-27 | 공통 모듈: SA 4개 분리 대신 common 하나로 두고 메시징만 나중에 분리하는 AI 추천을 수용 | kwonwoo | Claude Code | 수용 | 후보 | [기록](kwonwoo/2026-10-27-common-module-minimal-split.md) |
| 2026-10-27 | Gateway 스택: 현재 WebMVC 대신 SA 전제와 AI 추천대로 WebFlux로 전환 | kwonwoo | Claude Code | 수용 | 후보 | [기록](kwonwoo/2026-10-27-gateway-webflux.md) |
| 2026-10-27 | 멀티모듈 빌드: AI 진단·수정안(common 라이브러리화, 공통 의존성 루트 집약) 수용 | kwonwoo | Claude Code | 수용 | 아님 | [기록](kwonwoo/2026-10-27-multimodule-build-fix.md) |
| 2026-10-27 | 앱 검증 이관을 수용하고 Python 도구 유지안 대신 전체 Java·Gradle 통일 선택 | junhyun | Codex | 부분 수용 | 아님 | [기록](junhyun/2026-10-27-java-gradle-tools.md) |
| 2026-10-27 | Python 앱 검증 대신 내부 JUnit 테스트로 정리 | junhyun | Codex | **거부** | 아님 | [기록](junhyun/2026-10-27-junit-test-boundary.md) |
| 2026-10-27 | 인증 구현 보강 4개 수용과 항목별 커밋 | junhyun | Codex | 수용 | 아님 | [기록](junhyun/2026-10-27-auth-hardening.md) |
| 2026-10-27 | 공통 코드 작성 규칙 7개 수용, `Note:`는 필요한 지점에만 사용 | junhyun | Codex | 수용 | 아님 | [기록](junhyun/2026-10-27-common-code-style-rules.md) |
| 2026-10-27 | 신뢰 모델: AI 추천(서비스 JWT 직접 검증·RS256) 대신 SA 유지, 응답 형식은 SA #19로 정렬 | junhyun | Claude Code, Codex | **거부** | 아님 | [기록](junhyun/2026-10-27-auth-trust-follow-sa.md) |
| 2026-10-27 | D1에서 서비스가 사용자 JWT를 직접 검증하는 C 선택, D2는 비교 중 | junhyun | Codex | 수용 | 아님 | [기록](junhyun/2026-10-27-service-jwt-verification-choice.md) |
| 2026-10-27 | 브랜치 리뷰 후속 수정: 경로 판정 Gateway 일원화·로그인 보안 확장, 신뢰 모델은 후속 설계 | junhyun | Claude Code | 부분 수용 | 아님 | [기록](junhyun/2026-10-27-review-followup.md) |
| 2026-10-26 | Config 서버: AI의 '없이 .env + 프로필' 추천 대신 Config 서버를 둠 | kangmin | Claude Code, codex | **거부** | 아님 | [기록](kangmin/2026-10-26-config-server.md) |
| 2026-10-26 | 기술 스택: AI 추천 Java 25 대신 Java 21(팀 합의 전), Boot 4.1·Spring Cloud 2025.1 유지 | junhyun 결정 (조정 요청: kangmin) | Claude Code | 부분 수용 | 아님 | [기록](kangmin/2026-10-26-tech-stack-java21.md) |
| 2026-10-26 | 계층 구조: AI 추천 4계층을 팀이 확정 | 팀 (전달: kangmin) | Claude Code | 수용 | 아님 | [기록](kangmin/2026-10-26-layered-architecture-four-layers.md) |
| 2026-10-26 | 결정 기록 운영: 기존 항목 이력 덧붙이기 대신 대체됨 + 새 항목 | 팀 (전달: kangmin) | Claude Code | **거부** | 아님 | [기록](kangmin/2026-10-26-decision-record-supersede.md) |
| 2026-10-26 | 서비스 구성: 팀이 3개로 확정, 대기열·추첨·알림은 모듈로 두고 근거가 쌓이면 분리 | 팀 (전달: kangmin) | Claude Code, codex | 수용 | 아님 | [기록](kangmin/2026-10-26-service-composition-three-modules.md) |
| 2026-10-26 | 역할 분담: AI의 재검토 제안 대신 결정된 것으로 봄 | kangmin | Claude Code | **거부** | 아님 | [기록](kangmin/2026-10-26-role-assignment-not-reopen.md) |
| 2026-10-26 | API 응답 형식: AI의 기존 유지·6키 통일 대신 성공·실패 다른 형태 + traceId | 팀 (전달: kangmin) | Claude Code | 부분 수용 | 후보 | [기록](kangmin/2026-10-26-api-response-single-envelope.md) |
| 2026-10-26 | 대기열 정렬 기준: 팀원 지적(진입 시각 동점)을 검증하고 순번 카운터(seq) 선택 | kangmin (문제 제기: 대기열 담당) | Claude Code | 수용 | 후보 | [기록](kangmin/2026-10-26-queue-order-seq-counter.md) |
| 2026-10-26 | 두 에이전트 리뷰의 수정 사항 4건 수용 | junhyun | Codex 에이전트 2개·재검증 | 수용 | 아님 | [기록](junhyun/2026-10-26-two-agent-review-fixes.md) |
| 2026-10-26 | 공통 라이브러리만 구현한 범위를 거부하고 전체 워크스페이스로 확장 | junhyun | Codex | **거부** | 아님 | [기록](junhyun/2026-10-26-workspace-scope-correction.md) |
| 2026-10-26 | Config Server와 같은 저장소의 native 설정 폴더 선택 | junhyun | Codex | 수용 | 아님 | [기록](junhyun/2026-10-26-config-server-native.md) |
| 2026-10-26 | 인증 Redis는 우선 차단하고 팀과 논의하기로 함 | junhyun | Codex | 부분 수용 | 아님 | [기록](junhyun/2026-10-26-auth-redis-fail-closed.md) |
| 2026-10-26 | AI의 Java 25 제안 대신 Java 21 선택 | junhyun | Codex | **거부** | 아님 | [기록](junhyun/2026-10-26-java-21.md) |
| 2026-10-26 | SA 기준으로 공통 모듈 개인 초안 구현 | junhyun | Codex | 부분 수용 | 아님 | [기록](junhyun/2026-10-26-common-foundation-draft.md) |
| 2026-10-26 | 개발 일정 마일스톤: M1은 직접 정하고 M2–M4는 AI 분할안 수용 | junhyun | Claude Code | 수용 | 아님 | [기록](junhyun/2026-10-26-milestone-split.md) |
| 2026-10-26 | 원격 반영 방식: AI의 PR 경유 제안 대신 main 직접 push | junhyun | Claude Code | 부분 수용 | 아님 | [기록](junhyun/2026-10-26-push-learning-system-to-main.md) |
| 2026-10-23 | 서비스 경계: AI의 3개 구성·4개 절충 추천 대신 5개 분리 | junhyun (SA에는 팀 결정) | Claude Code | **거부** | 후보 | [기록](junhyun/2026-10-23-service-boundary-five-services.md) |

## Notion "AI 활용 설계 검증 로그"로 옮길 때

| Notion 열 | 개별 기록의 위치 |
|---|---|
| 검증 항목 | `title`, `area` |
| AI 프롬프트 (요약) | 맥락 > AI 활용 방식 |
| AI 피드백 (요약) | AI 제안 |
| 수용 여부 | `verdict` |
| 판단 근거 | 판단 > 근거, 검토한 대안 |

발표 대표 사례로 쓸 때는 `presentation: 후보`인 기록에서 고르고, `검증` 칸(빌드·테스트·측정 결과)이 채워진 것을 우선한다.
