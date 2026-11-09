# junhyun AI 판단 기록

junhyun이 AI 응답·제안에 내린 판단이다. 작성 절차는 [`ai-judgment` 스킬](../../../.agents/skills/ai-judgment/SKILL.md)을 따른다. 최신이 위로 온다. `공통`이 `예`인 기록은 [팀 공통 판단](../README.md#팀-공통-판단)에도 올라간다.

## 개별 기록

거부 판단은 굵게 표시한다.

| 날짜 | 판단 대상 | AI | 판단 | 공통 | 발표 후보 | 기록 |
|---|---|---|---|---|---|---|
| 2026-11-08 | 배포 측정 기반(ISE-191)을 M4로 두되 측정 전에 끝내고, 측정용 토큰은 측정 동안만 측정 전용 키로 바꿔 띄우기로 함 | Claude Code | 수용 | 예 | 후보 | [기록](2026-11-08-deploy-measure-token-key-swap.md) |
| 2026-11-09 | TX1 커밋 응답 유실 대책으로 AI가 낸 "재시도가 REQUESTED를 이어받아 승인" 방안을 거부하고 한계를 ADR로 기록함(ISE-169) | Claude Code | **거부** | 예 | 후보 | [기록](2026-11-09-ise169-reject-retry-takeover.md) |
| 2026-11-09 | 백로그 설계안 중 AI 상담은 Claude API 키를 받아 Haiku 5.5로 시작하고, 토스 테스트 키는 수요일에 받고, 시연 장애 버튼은 Gateway 운영자 경로로 두기로 함 | Claude Code | 부분 수용 | 예 | 아님 | [기록](2026-11-09-backlog-design-decisions.md) |
| 2026-11-08 | Linear 정리 계획 중 좌석 조회 일정은 두 담당이 합의하게 하고, 내 백로그 줄이기 제안은 거두고 11/13까지 모두 끝내기로 함 | Claude Code | 부분 수용 | 예 | 아님 | [기록](2026-11-08-linear-cleanup-plan-feedback.md) |
| 2026-11-05 | 시연 모드 Grafana 링크를 localhost:3000에 고정한 AI 설계를 거두고, 배포 서버 Grafana를 팀원 IP로 열어 화면을 연 호스트 기준으로 잇게 함 | Claude Code | **거부** | 예 | 아님 | [기록](2026-11-05-grafana-link-deploy-host.md) |
| 2026-11-05 | 배포 서버 앱의 GC는 리허설(11/6)까지 Serial로 두고, 다음 주 부하 측정 전에 G1을 명시하기로 함 | Claude Code | 수용 | 예 | 아님 | [기록](2026-11-05-deploy-g1-after-rehearsal.md) |
| 2026-11-05 | 부하 테스트에 대비해 배포 EC2를 flex(m7i-flex.xlarge)에서 같은 크기의 일반 인스턴스(m7i.xlarge)로 바꿈 | Claude Code | 수용 | 예 | 아님 | [기록](2026-11-05-deploy-ec2-m7i-standard-for-load-test.md) |
| 2026-11-05 | 핵심 흐름만 보이던 AI의 화면 구성을 실제 서비스형 레이아웃·그림 에셋으로 바꾸게 함(ISE-187) | Claude Code | 부분 수용 | 예 | 아님 | [기록](2026-11-05-frontend-service-like-layout.md) |
| 2026-11-05 | 등록 기록이 없을 때도 자동결제 카드 해지 버튼을 둔 AI 설계를 거두고 버튼을 없앰(ISE-186) | Claude Code | **거부** | 아니오 | 아님 | [기록](2026-11-05-billing-card-no-revoke-without-record.md) |
| 2026-11-04 | 지출 한도로 멈춘 배포·Jenkins 서버를 한도를 올려 살리되, 그 시점을 다음 날(11/5)로 미룸 | Claude Code | 부분 수용 | 예 | 아님 | [기록](2026-11-04-spend-limit-raise-tomorrow.md) |
| 2026-11-04 | 배포는 EC2 한 대로 시작하고, 서버를 멈춘 기간(11/7~11/12)에 RDS 등 매니지드 서비스 분리를 검토하기로 함(ADR 0047) | Claude Code | 수용 | 예 | 후보 | [기록](2026-11-04-single-ec2-then-review-managed.md) |
| 2026-11-04 | 리허설 일정에 맞춰 측정 전에 배포 EC2를 16GB급으로 정하고 AWS Paid plan으로 바꿈 | Claude Code | 수용 | 예 | 아님 | [기록](2026-11-04-deploy-ec2-16gb-paid-plan.md) |
| 2026-11-04 | 로컬 테스트 환불의 반복 알림을 무음으로 끈 AI 처리를 거두고 로컬 데이터를 한 번 정상화(close-failed와 같은 전이를 SQL로)하게 함 | Claude Code | **거부** | 아니오 | 아님 | [기록](2026-11-04-local-refund-alert-fix-data.md) |
| 2026-11-04 | M3 배포 순서(173·178 → 176 → 174·177 → 175)를 받아들이고 프론트 nginx(ISE-109)를 M4에서 M3로 당겨 이미지 워크플로(ISE-173)와 함께 함 | Claude Code | 부분 수용 | 예 | 아님 | [기록](2026-11-04-m3-deploy-order-with-ise109.md) |
| 2026-11-04 | 배포용 AWS 계정 운영: 시드니 리전 유지(서울 전환 안 함), 팀원은 각자 SSH 키와 IP로 접속하고 콘솔 초대는 필요한 사람만 | Claude Code | 수용 | 예 | 아님 | [기록](2026-11-04-aws-region-and-team-access.md) |
| 2026-11-03 | 배포·관측·부하 측정 설계: AI 추천 대부분 수용, Jenkins를 더하고 main 머지 자동 배포·오픈 피크 전체 시나리오를 고름, 요청 로그는 공통 필터로 | Claude Code | 부분 수용 | 예 | 후보 | [기록](2026-11-03-deploy-observability-loadtest-design.md) |
| 2026-11-03 | 반효승 PR #282(DLQ 처리기)·#283(대사 내부 조회 API) AI 리뷰를 PR 코멘트로 남기고, 대사 응답 모양은 #283에 맞춰 결제 쪽 어댑터를 고치기로 함 | Claude Code, Opus 리뷰 | 수용 | 예 | 아님 | [기록](2026-11-03-review-hyoseung-dlq-and-reconciliation-api.md) |
| 2026-11-03 | 대사 RC-5(예매 금액)·RC-6(사용자) 불일치를 APPROVED 결제로만 보도록 ADR 0029를 고침(환불하면 닫히는 경로) | Claude Code, Opus 리뷰 | 수용 | 예 | 아님 | [기록](2026-11-03-reconciliation-rc5-rc6-approved-only.md) |
| 2026-11-03 | herdr 작업자 패널 입력창의 Claude Code 추천 입력을 사용자 판단으로 읽은 AI 해석을 바로잡음 | Claude Code | **거부** | 아니오 | 아님 | [기록](2026-11-03-worker-pane-suggestion-not-user-input.md) |
| 2026-11-02 | 공연 화면에 없는 필드를 백엔드에 먼저 요청하자는 제안 대신 백엔드 구현 그대로 쓰고 빈 곳은 나중에 고칠 것으로 기록 | Claude Code | **거부** | 예 | 아님 | [기록](2026-11-02-frontend-follows-backend-as-is.md) |
| 2026-11-02 | Gateway가 만료된 Access 토큰에 401 `TOKEN_EXPIRED`를 주도록 SA대로 고치는 안(1안) 선택, SA를 단일 401로 바꾸는 안은 고르지 않음 | Claude Code | 수용 | 예 | 아님 | [기록](2026-11-02-gateway-token-expired-code.md) |
| 2026-11-02 | 프론트 Refresh 토큰을 탭 단위 `sessionStorage`에 두는 제안(ADR 0040 4절) | Claude Code | 수용 | 예 | 아님 | [기록](2026-11-02-refresh-token-session-storage.md) |
| 2026-11-02 | 좌석 선택 샘플과 디자인 시스템 초안(이선좌 워드마크, 인디고, 검정+체크 선택 좌석)을 그대로 확정 | Claude Code | 수용 | 예 | 아님 | [기록](2026-11-02-design-system-sample-accepted.md) |
| 2026-11-02 | "Notion보다 저장소 기록 우선"을 개인 전역 지침에 넣은 AI 처리를 바로잡아 팀 규칙(`AGENTS.md` 대전제 4)으로 올림 | Claude Code | **거부** | 예 | 아님 | [기록](2026-11-02-docs-first-as-team-rule.md) |
| 2026-11-02 | 프론트 결정을 Notion 결정 기록에 반영하자는 제안 대신 저장소 docs에 기록 | Claude Code | **거부** | 예 | 아님 | [기록](2026-11-02-records-in-docs-over-notion.md) |
| 2026-11-02 | 프론트·Gateway 연결: BFF 대신 nginx 중계(A), 실서비스 구조(BFF·앞단 대기실)는 학습 기록으로 남기고 백엔드 집중 | Claude Code | 수용 | 예 | 아님 | [기록](2026-11-02-frontend-relay-over-bff.md) |
| 2026-11-02 | 프론트를 팀 결정 #7(정적 HTML·JS) 대신 저장소 안 `frontend/`의 React SPA로 전환, 추천 스택 채택, 화면 범위는 전부 계획에 넣고 단계적 구현 (ADR 0040) | Claude Code | 수용 | 예 | 아님 | [기록](2026-11-02-frontend-react-spa.md) |
| 2026-10-30 | Mock PG 설계 Opus 리뷰 지적 전부 반영, 계약 테스트는 bootJar 별도 프로세스, 처리 중 승인 재요청은 409, compose 프로필은 팀 확인 대기 | Claude Code, Opus 리뷰 | 수용 | 예 | 후보 | [기록](2026-10-30-mock-pg-design-review.md) |
| 2026-10-28 | Outbox·처리 이력 코드를 payment-service에 둔 AI 판단을 되돌려 common 모듈로 옮기기로 함 | Claude Code, Opus 리뷰 | 수용 | 예 | 아님 | [기록](2026-10-28-outbox-code-location.md) |
| 2026-10-27 | 결제 상세 설계 빈틈 11개 결정과 Opus 검토 반영(10/28 갱신: 예매당 결제 하나, 환불 자동 복구, Ops 명령, 대사, 불변 조건) | Claude Code | 수용 | 예 | 후보 | [기록](2026-10-27-payment-design-gaps.md) |
| 2026-10-27 | 앱 검증 이관을 수용하고 Python 도구 유지안 대신 전체 Java·Gradle 통일 선택 | Codex | 부분 수용 | 예 | 아님 | [기록](2026-10-27-java-gradle-tools.md) |
| 2026-10-27 | Python 앱 검증 대신 내부 JUnit 테스트로 정리 | Codex | **거부** | 예 | 아님 | [기록](2026-10-27-junit-test-boundary.md) |
| 2026-10-27 | 인증 구현 보강 4개 수용과 항목별 커밋 | Codex | 수용 | 예 | 아님 | [기록](2026-10-27-auth-hardening.md) |
| 2026-10-27 | 공통 코드 작성 규칙 7개 수용, `Note:`는 필요한 지점에만 사용 | Codex | 수용 | 예 | 아님 | [기록](2026-10-27-common-code-style-rules.md) |
| 2026-10-27 | 신뢰 모델: AI 추천(서비스 JWT 직접 검증·RS256) 대신 SA 유지, 응답 형식은 SA #19로 정렬 | Claude Code, Codex | **거부** | 예 | 아님 | [기록](2026-10-27-auth-trust-follow-sa.md) |
| 2026-10-27 | D1에서 서비스가 사용자 JWT를 직접 검증하는 C 선택, D2는 비교 중 | Codex | 수용 | 예 | 아님 | [기록](2026-10-27-service-jwt-verification-choice.md) |
| 2026-10-27 | 브랜치 리뷰 후속 수정: 경로 판정 Gateway 일원화·로그인 보안 확장, 신뢰 모델은 후속 설계 | Claude Code | 부분 수용 | 예 | 아님 | [기록](2026-10-27-review-followup.md) |
| 2026-10-26 | 두 에이전트 리뷰의 수정 사항 4건 수용 | Codex 에이전트 2개·재검증 | 수용 | 예 | 아님 | [기록](2026-10-26-two-agent-review-fixes.md) |
| 2026-10-26 | 공통 라이브러리만 구현한 범위를 거부하고 전체 워크스페이스로 확장 | Codex | **거부** | 예 | 아님 | [기록](2026-10-26-workspace-scope-correction.md) |
| 2026-10-26 | Config Server와 같은 저장소의 native 설정 폴더 선택 | Codex | 수용 | 예 | 아님 | [기록](2026-10-26-config-server-native.md) |
| 2026-10-26 | 인증 Redis는 우선 차단하고 팀과 논의하기로 함 | Codex | 부분 수용 | 예 | 아님 | [기록](2026-10-26-auth-redis-fail-closed.md) |
| 2026-10-26 | AI의 Java 25 제안 대신 Java 21 선택 | Codex | **거부** | 예 | 아님 | [기록](2026-10-26-java-21.md) |
| 2026-10-26 | SA 기준으로 공통 모듈 개인 초안 구현 | Codex | 부분 수용 | 예 | 아님 | [기록](2026-10-26-common-foundation-draft.md) |
| 2026-10-26 | 개발 일정 마일스톤: M1은 직접 정하고 M2–M4는 AI 분할안 수용 | Claude Code | 수용 | 예 | 아님 | [기록](2026-10-26-milestone-split.md) |
| 2026-10-26 | 원격 반영 방식: AI의 PR 경유 제안 대신 main 직접 push | Claude Code | 부분 수용 | 예 | 아님 | [기록](2026-10-26-push-learning-system-to-main.md) |
| 2026-10-23 | 서비스 경계: AI의 3개 구성·4개 절충 추천 대신 5개 분리 | Claude Code | **거부** | 예 | 후보 | [기록](2026-10-23-service-boundary-five-services.md) |

## 일지

작은 판단을 하루 한 파일에 한 줄씩 남긴다.

| 날짜 | 일지 |
|---|---|
| 2026-11-05 | [daily/2026-11-05.md](daily/2026-11-05.md) |
| 2026-11-04 | [daily/2026-11-04.md](daily/2026-11-04.md) |
| 2026-11-03 | [daily/2026-11-03.md](daily/2026-11-03.md) |
| 2026-11-02 | [daily/2026-11-02.md](daily/2026-11-02.md) |
| 2026-10-30 | [daily/2026-10-30.md](daily/2026-10-30.md) |
| 2026-10-28 | [daily/2026-10-28.md](daily/2026-10-28.md) |
| 2026-10-26 | [daily/2026-10-26.md](daily/2026-10-26.md) |
| 2026-10-23 | [daily/2026-10-23.md](daily/2026-10-23.md) |
