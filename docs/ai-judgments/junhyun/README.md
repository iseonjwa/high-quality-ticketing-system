# junhyun AI 판단 기록

junhyun이 AI 응답·제안에 내린 판단이다. 작성 절차는 [`ai-judgment` 스킬](../../../.agents/skills/ai-judgment/SKILL.md)을 따른다. 최신이 위로 온다. `공통`이 `예`인 기록은 [팀 공통 판단](../README.md#팀-공통-판단)에도 올라간다.

## 개별 기록

거부 판단은 굵게 표시한다.

| 날짜 | 판단 대상 | AI | 판단 | 공통 | 발표 후보 | 기록 |
|---|---|---|---|---|---|---|
| 2026-11-14 | 발표 자료 세 가지(2분 영상, Notion 브로셔, 10분 발표)의 축과 형식을 AI 선택지에서 고르고, 브로셔 형식·영상 소리·AI 축 줄기·발표자·디자인은 직접 정함 | Claude Code | 부분 수용 | 예 | 아님 | [기록](2026-11-14-presentation-materials-plan.md) |
| 2026-11-13 | 호출 한도가 있어 CodeRabbit을 넣지 않고, AI 코드 리뷰는 저장소 프롬프트와 스크립트로만 하기로 함 | Claude Code | **거부** | 예 | 후보 | [기록](2026-11-13-no-coderabbit-prompt-review-only.md) |
| 2026-11-13 | 다른 조 저장소에서 스택과 결정이 아니라 형식과 판단 방식만 가져오기로 하고, 바로 할 것 두 가지와 전체 적용 설계·CodeRabbit 적용까지 맡김 | Claude Code | 수용 | 예 | 후보 | [기록](2026-11-13-borrow-formats-not-stacks-from-other-teams.md) |
| 2026-11-13 | 팀원 지시문이 Claude Code 세션만 전제했다는 지적으로, 필수 산출물 작업을 팀원 모두가 도구와 상관없이 돌리게 바꿈 | Claude Code | **거부** | 예 | 아님 | [기록](2026-11-13-deliverables-for-all-teammates-any-tool.md) |
| 2026-11-13 | 발표 전 보완 제안(빈 필수 산출물, AI 코드 검토 방법, AI 대표 사례)을 받아들이고, 11/11 취소된 Synthetic Data를 발제 필수 항목으로 되살림 | Claude Code | 수용 | 예 | 후보 | [기록](2026-11-13-fill-presentation-gaps-restore-synthetic-data.md) |
| 2026-11-12 | 추첨 쪽이 요청한 PG 허가 조정(ISE-236)에 일반 결제 허가는 17보다 큰 24~30(PAY1-b 측정 뒤 확정), 자동결제는 전용 그룹 `pg-billing` 3으로 답하기로 함 | Claude Code | 수용 | 예 | 후보 | [기록](2026-11-12-pg-permits-approve-and-billing-group.md) |
| 2026-11-11 | 시스템 구조도의 "EC2 컨테이너 분리"를 컨테이너별 메모리 상한 표시로 읽은 AI 해석을 바로잡고, Jenkins EC2와 부하 발생기 EC2를 그림에 넣게 함 | Claude Code | **거부** | 아니오 | 아님 | [기록](2026-11-11-system-diagram-ec2-scope.md) |
| 2026-11-11 | AI 브리핑이 멘션 알림만 보고 이미 끝난 ISE-224 웹훅 일(서버 `.env`에 이미 있음, 이슈 Done)을 남은 일로 넣었다고 지적함 | Claude Code | **거부** | 아니오 | 아님 | [기록](2026-11-11-briefing-listed-finished-webhook.md) |
| 2026-11-11 | 결제 쪽 취소 환불을 위해 기다리던 예매 취소 API(ISE-206)를 직접 만들기로 하고, 취소 정책(공연 시작 전까지·추첨 예매 제외·결제 조회로 환불 상태 표시·담당 변경)은 AI 추천안을 받아들임 | Claude Code | 수용 | 예 | 후보 | [기록](2026-11-11-reservation-cancel-takeover-policy.md) |
| 2026-11-10 | AI의 "남은 마감 작업" 정리가 내게 배정된 이슈만 훑어 멘션으로 받은 일(ISE-227·ISE-192)을 빠뜨렸다고 지적함 | Claude Code | **거부** | 아니오 | 후보 | [기록](2026-11-10-remaining-work-missed-mentions.md) |
| 2026-11-10 | 토스 테스트 결제창도 실제 카드 인증이 필요해 "토스연동 건너뛰고 결제하기"를 두기로 하고, 결제마다 PG를 기록하며 빌링은 늘 Mock으로 보내는 추천안을 수용함 | Claude Code | 수용 | 예 | 후보 | [기록](2026-11-10-toss-mock-bypass-per-payment-pg.md) |
| 2026-11-09 | 오류 응답이 Accept에 따라 비는 결함을 상담 서비스 안이 아니라 공통 예외 처리기(common)에서 고치기로 함(ISE-221) | Claude Code | 수용 | 예 | 아님 | [기록](2026-11-09-error-response-json-in-common.md) |
| 2026-11-09 | 상담 스트림에 error 이벤트를 두지 않고, 사용자가 멈춘 요청도 요청 제한과 일일 예산에 넣기로 함 | Claude Code | 수용 | 아니오 | 아님 | [기록](2026-11-09-assistant-stream-error-and-cancel-counting.md) |
| 2026-11-09 | 예매 문의 챗봇을 /help 페이지로 두자는 추천 대신 모든 화면에서 여는 떠 있는 위젯으로 바꾸기로 함 | Claude Code | **거부** | 예 | 아님 | [기록](2026-11-09-assistant-floating-widget.md) |
| 2026-11-09 | '지금 예매 중' 카드를 줄였다는 AI의 말과 달리 포스터가 그대로 길었던 것을 지적함 | Claude Code | **거부** | 아니오 | 아님 | [기록](2026-11-09-home-card-shorten-not-applied.md) |
| 2026-11-09 | 홈 리뉴얼 시안 3종 중 AI 추천(A 배치 + C 로고) 대신 시안 B와 B 로고를 고르고, 라이트·다크 두 모드와 굵기·카드 비율 수정을 요청함 | Claude Code | 부분 수용 | 예 | 아님 | [기록](2026-11-09-home-renewal-draft-b-light-dark.md) |
| 2026-11-08 | 배포 측정 기반(ISE-191)을 M4로 두되 측정 전에 끝내고, 측정용 토큰은 측정 동안만 측정 전용 키로 바꿔 띄우기로 함 | Claude Code | 수용 | 예 | 후보 | [기록](2026-11-08-deploy-measure-token-key-swap.md) |
| 2026-11-09 | 토스 어댑터의 orderId를 토큰에서 만들고, 늦은 승인 방지는 Prevented를 내지 않으며, 빌링은 지원하지 않고, 배포 기본은 mock으로 한 추천안을 수용함 | Claude Code | 수용 | 예 | 아님 | [기록](2026-11-09-toss-adapter-order-id-and-late-approval.md) |
| 2026-11-09 | 결제 건별 슬랙 알림(ISE-183)의 세부 여섯 가지(결제 시각 표기, 금액 불일치 합치기, 해결 메시지 없음, 대사 10건 상한, 환불 문구)를 추천안대로 정함 | Claude Code | 수용 | 예 | 아님 | [기록](2026-11-09-payment-event-alert-details.md) |
| 2026-11-09 | TX1 커밋 응답 유실 대책으로 AI가 낸 "재시도가 REQUESTED를 이어받아 승인" 방안을 거부하고 한계를 ADR로 기록함(ISE-169) | Claude Code | **거부** | 예 | 후보 | [기록](2026-11-09-ise169-reject-retry-takeover.md) |
| 2026-11-09 | 백로그 설계안 중 AI 상담은 Claude API 키를 받아 Haiku 5.5로 시작하고, 토스 테스트 키는 수요일에 받고, 시연 장애 버튼은 Gateway 운영자 경로로 두기로 함 | Claude Code | 부분 수용 | 예 | 아님 | [기록](2026-11-09-backlog-design-decisions.md) |
| 2026-11-08 | 프론트 사용자가 API를 직접 쏘는 사람보다 대기열에 늦게 선다는 문제를 제기하고 AI 답(rate limit·사전 대기실 등)을 받았으나 기록만 남김 | Claude Code | 보류 | 예 | 후보 | [기록](2026-11-08-queue-fairness-direct-api-record-only.md) |
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
| 2026-11-13 | [daily/2026-11-13.md](daily/2026-11-13.md) |
| 2026-11-12 | [daily/2026-11-12.md](daily/2026-11-12.md) |
| 2026-11-11 | [daily/2026-11-11.md](daily/2026-11-11.md) |
| 2026-11-10 | [daily/2026-11-10.md](daily/2026-11-10.md) |
| 2026-11-09 | [daily/2026-11-09.md](daily/2026-11-09.md) |
| 2026-11-05 | [daily/2026-11-05.md](daily/2026-11-05.md) |
| 2026-11-04 | [daily/2026-11-04.md](daily/2026-11-04.md) |
| 2026-11-03 | [daily/2026-11-03.md](daily/2026-11-03.md) |
| 2026-11-02 | [daily/2026-11-02.md](daily/2026-11-02.md) |
| 2026-10-30 | [daily/2026-10-30.md](daily/2026-10-30.md) |
| 2026-10-28 | [daily/2026-10-28.md](daily/2026-10-28.md) |
| 2026-10-26 | [daily/2026-10-26.md](daily/2026-10-26.md) |
| 2026-10-23 | [daily/2026-10-23.md](daily/2026-10-23.md) |
