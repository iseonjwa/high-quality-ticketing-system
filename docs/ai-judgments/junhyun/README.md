# junhyun AI 판단 기록

junhyun이 AI 응답·제안에 내린 판단이다. 작성 절차는 [`ai-judgment` 스킬](../../../.agents/skills/ai-judgment/SKILL.md)을 따른다. 최신이 위로 온다. `공통`이 `예`인 기록은 [팀 공통 판단](../README.md#팀-공통-판단)에도 올라간다.

## 개별 기록

거부 판단은 굵게 표시한다.

| 날짜 | 판단 대상 | AI | 판단 | 공통 | 발표 후보 | 기록 |
|---|---|---|---|---|---|---|
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
| 2026-11-02 | [daily/2026-11-02.md](daily/2026-11-02.md) |
| 2026-10-30 | [daily/2026-10-30.md](daily/2026-10-30.md) |
| 2026-10-28 | [daily/2026-10-28.md](daily/2026-10-28.md) |
| 2026-10-26 | [daily/2026-10-26.md](daily/2026-10-26.md) |
| 2026-10-23 | [daily/2026-10-23.md](daily/2026-10-23.md) |
