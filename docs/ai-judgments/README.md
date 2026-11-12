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
| [junhyun](junhyun/README.md) | 개별 기록 51건, 일지 5일 |
| [kangmin](kangmin/README.md) | 개별 기록 40건, 일지 8일 |
| [kwonwoo](kwonwoo/README.md) | 개별 기록 13건, 일지 4일 |
| [youngeun](youngeun/README.md) | 개별 기록 4건, 일지 0일 |

## 팀 공통 판단

최신이 위로 오게 한다. 거부 판단은 발제 제출 기준(1건 이상)을 채우는지 바로 볼 수 있게 굵게 표시한다.

| 날짜 | 판단 대상 | 판단자 | AI | 판단 | 발표 후보 | 기록 |
|---|---|---|---|---|---|---|
| 2026-11-12 | 측정표 답글·결제와 예매 DB 풀 contract·요청 로그 B안 설계·R0 준비에 대한 AI 추천 9건(D1~D9)을 그대로 받아들임 | kangmin | Claude Code | 수용 | 아님 | [기록](kangmin/2026-11-12-accept-d1-d9-posting-and-h2-plan.md) |
| 2026-11-11 | 배포 서버 7차에서 요청 로그 비용이 확인되자, 운영 요청 로그를 실패·느린 요청만 남기는 안(B)을 고름 | kangmin | Claude Code | 수용 | 후보 | [기록](kangmin/2026-11-11-request-log-failures-and-slow-only.md) |
| 2026-11-11 | **H1 배포와 7차 측정을 내일 아침에 하자는 AI 추천을 거부하고, H1 PR이 dev에 머지되면 오늘 이어서 배포·측정함** | kangmin | Claude Code | **거부** | 아님 | [기록](kangmin/2026-11-11-run-7th-measurement-today.md) |
| 2026-11-11 | **Gateway 동시 처리 수 상한 기본값을 128로 올리자는 6차 세션 제안을 거부하고, p95 여유를 이유로 64 유지를 추천한 메인 세션 안을 받아들임** | kangmin | Claude Code | **거부** | 후보 | [기록](kangmin/2026-11-11-keep-in-flight-limit-64.md) |
| 2026-11-11 | Gateway CPU 포화와 OOM을 막는 첫 장치로 맨 앞 동시 처리 수 상한을 고르고, 설계안·프런트 표시·첫 상한 64를 AI 추천대로 정함 | kangmin | Claude Code | 수용 | 후보 | [기록](kangmin/2026-11-11-gateway-in-flight-limit-first.md) |
| 2026-11-11 | Gateway CPU의 약 24%를 쓰는 관측(추적·지표·로그 문맥) 축소를 위험하다고 보고 보류함 | kangmin | Claude Code | 보류 | 아님 | [기록](kangmin/2026-11-11-defer-gateway-observability-reduction.md) |
| 2026-11-11 | **긴 측정 뒤 배포 실패를 막으려고 Zipkin 힙을 늘리자는 AI 추천을 거부하고, 메모리 설정은 두고 스케줄러 Redis 스팬을 근원에서 줄이며 이미지 받기 재시도를 더함** | kangmin | Claude Code | **거부** | 후보 | [기록](kangmin/2026-11-11-reduce-scheduler-redis-spans-keep-zipkin-memory.md) |
| 2026-11-11 | **한 서버 자원 격리 개선(M1 메모리 예산 등)을 contract 이슈로 인프라 담당과 먼저 합의하자는 AI 추천을 거부하고, 측정과 함께 순서대로 바로 진행함** | kangmin | Claude Code | **거부** | 아님 | [기록](kangmin/2026-11-11-resource-isolation-without-infra-agreement.md) |
| 2026-11-11 | **Gateway CPU 포화를 맨 앞 유입 제한으로 막자는 AI 추천을 거부하고, auth-redis 차단기를 단계적으로 예민하게 바꿔 포화 전에 열리는지 먼저 실험함** | kangmin | Claude Code | **거부** | 후보 | [기록](kangmin/2026-11-11-breaker-first-for-gateway-cpu-saturation.md) |
| 2026-11-11 | 결제 쪽 취소 환불을 위해 기다리던 예매 취소 API(ISE-206)를 직접 만들기로 하고, 취소 정책(공연 시작 전까지·추첨 예매 제외·결제 조회로 환불 상태 표시·담당 변경)은 AI 추천안을 받아들임 | junhyun | Claude Code | 수용 | 후보 | [기록](junhyun/2026-11-11-reservation-cancel-takeover-policy.md) |
| 2026-11-11 | PR 487 대기열 담당 리뷰 반영에서 오픈 피크용 거절 경보를 따로 두자는 AI 추천(A안)을 받아들임 | kangmin | Claude Code | 수용 | 아님 | [기록](kangmin/2026-11-11-pr487-review-peak-alert.md) |
| 2026-11-11 | Gateway auth-redis 운영값을 G1 2회 값(필터 시한 200ms·Lettuce 300ms)으로 정하고 bulkhead 거절 비율 경보를 같이 넣자는 AI 추천을 받아들임 | kangmin | Claude Code | 수용 | 후보 | [기록](kangmin/2026-11-11-gateway-auth-redis-operating-values.md) |
| 2026-11-11 | F1 장애 주입의 실행 주체(이번만 에이전트 SSH 실행 예외)·Kafka 정지·NOT_SENT 테스트 대체·경보 범위·선점 만료 앞당기기를 AI 추천안대로 정함 | kangmin | Claude Code | 수용 | 후보 | [기록](kangmin/2026-11-11-f1-execution-decisions.md) |
| 2026-11-11 | F1-a 결제 서버 강제 종료를 첫 결제 행 감지 시 자동 kill로 바꾸고 추첨석은 절차서대로 20석 유지 | kangmin | Claude Code | 수용 | 아님 | [기록](kangmin/2026-11-11-f1a-auto-kill-trigger.md) |
| 2026-11-11 | F1을 측정 모드 없이 하자는 AI 추천을 거부하고, 배포 서버 테스트는 측정 모드 on과 팀 알림을 필수로 함 | kangmin | Claude Code | 거부 | 후보 | [기록](kangmin/2026-11-11-f1-measure-mode-required.md) |
| 2026-11-11 | 자동결제·일반 결제 한도 개선에서 전용 허가 d1과 Erlang B 산정은 AI 추천을 받아들이고, 일반 결제 목표는 추천 없이 초당 30으로 정함 | kangmin | Claude Code | 부분 수용 | 후보 | [기록](kangmin/2026-11-11-lottery-billing-isolation-and-approve-size.md) |
| 2026-11-10 | auth-redis 재측정 값을 느린 호출 100ms·80%, 시한 200ms, 벌크헤드 50(거절은 차단기 집계에서 제외), 실패율 50%로 하자는 AI 추천을 받아들임 | kangmin | Claude Code | 수용 | 후보 | [기록](kangmin/2026-11-10-auth-redis-timeout-bulkhead-measure-values.md) |
| 2026-11-10 | 토스 테스트 결제창도 실제 카드 인증이 필요해 "토스연동 건너뛰고 결제하기"를 두기로 하고, 결제마다 PG를 기록하며 빌링은 늘 Mock으로 보내는 추천안을 수용함 | junhyun | Claude Code | 수용 | 후보 | [기록](junhyun/2026-11-10-toss-mock-bypass-per-payment-pg.md) |
| 2026-11-10 | 이슈 자동 완료를 피하려고 PR 제목·브랜치에서 이슈 번호를 뺀 AI 방식을 거부하고, 번호를 넣은 채 연결 종류로 완료를 막게 함 | kangmin | Claude Code | **거부** | 아님 | [기록](kangmin/2026-11-10-keep-issue-number-in-pr-title.md) |
| 2026-11-10 | 11/11 회의에서 개선안을 정하고 11/13에 재측정하자는 AI 일정을 거부하고, 개선 적용과 재측정을 최대한 앞당기기로 함 | kangmin | Claude Code | **거부** | 아님 | [기록](kangmin/2026-11-10-pull-forward-improvement-remeasure.md) |
| 2026-11-10 | 서버 명령을 측정자가 직접 치고 에이전트는 안내만 하는 방식 대신, 11/10 측정에서는 에이전트가 배포 서버·발생기 명령을 직접 실행하게 함 | kangmin | Claude Code | **거부** | 아님 | [기록](kangmin/2026-11-10-agent-runs-server-commands-during-measurement.md) |
| 2026-11-10 | 추첨 배포 측정 전 점검의 추천(L1 503 메시지 태그, 응모 3만 명·시각, 반복 조건, PAY_RATE 20·30)을 그대로 받아들임 | kangmin | Claude Code | 수용 | 아님 | [기록](kangmin/2026-11-10-lottery-server-measurement-conditions.md) |
| 2026-11-10 | E 값 Q1 재측정에서 차단기가 열리지 않아 데운 단계 멈춤 기준을 오류율 10%·p95 1초로 바꾸고 워밍업의 초당 3,000명 구간을 뺌 | youngeun | Claude Code | 부분 수용 | 후보 | [기록](youngeun/2026-11-10-e-values-warm-stop-rule.md) |
| 2026-11-10 | 식은 Gateway Q1 결과 문서에서 실패 1.0s를 "제한시간 1s 실패"가 아니라 "100ms 시한 초과의 기록값일 가능성"(dev #463 근거)으로 쓰기로 함 | youngeun | Claude Code | 수용 | 후보 | [기록](youngeun/2026-11-10-slow-call-threshold-cold-gateway.md) |
| 2026-11-10 | bulkhead 비교 전에 워밍업 방식부터 확인하고 덤 몰림 포함 워밍업을 표준으로 정함. bulkhead 25·100 모두 Q1 한도 같음 | youngeun | Claude Code | 수용 | 후보 | [기록](youngeun/2026-11-10-bulkhead-comparison-warmup-standard.md) |
| 2026-11-10 | **배포 Q1 auth-redis 차단기 열림에 측정용 차단기 기준 완화를 거부하고 워밍업 뒤 재측정을 고름** | youngeun | Claude Code | **거부** | 후보 | [기록](youngeun/2026-11-10-no-breaker-relax-for-measurement.md) |
| 2026-11-09 | "Gateway에는 벌크헤드가 없다"는 AI 조사 결과를 팀원 지적으로 다시 확인해 틀렸다고 판단함(기본 벌크헤드 동시 25) | kangmin | Claude Code | **거부** | 후보 | [기록](kangmin/2026-11-09-gateway-has-default-bulkhead.md) |
| 2026-11-09 | 측정 전 main 반영에 팀원 PR을 넣을지 묻자는 제안을 두고, 팀원 PR은 측정하지 않을 때 상황을 봐서 넘기기로 함 | kangmin | Claude Code | **거부** | 아님 | [기록](kangmin/2026-11-09-team-prs-not-in-freeze-merge.md) |
| 2026-11-09 | 측정 모드 알림을 성능-모니터링 채널에 두지 않고 별도 채널로 옮기고, 배포 알림처럼 꾸미기로 함 | kangmin | Claude Code | **거부** | 아님 | [기록](kangmin/2026-11-09-measure-mode-alert-own-channel.md) |
| 2026-11-09 | dev→main을 측정 전 한 번에 넘기는 대신, 시험용으로 먼저 넘기고 이후에 다시 넘기기로 함 | kangmin | Claude Code | 부분 수용 | 아님 | [기록](kangmin/2026-11-09-main-merge-test-first.md) |
| 2026-11-09 | 측정 모드를 켜고 끌 때 슬랙 자동 알림을 붙이기로 함(사람 알림만으로 두지 않음) | kangmin | Claude Code | 수용 | 아님 | [기록](kangmin/2026-11-09-measure-mode-slack-alert.md) |
| 2026-11-09 | 기록 검사 훅 속도 개선(약 25배)을 변이 31가지 비교로 검증한 뒤 올리고, 한글 경로 표시 개선을 함께 넣음 | kangmin | Claude Code | 수용 | 후보 | [기록](kangmin/2026-11-09-records-check-25x-faster.md) |
| 2026-11-09 | 에이전트 k6 실행 토큰 과다(진행 표시줄 출력)와 느린 기록 검사 훅 진단을 중 k6 출력 건만 이슈로 올리기로 함(ISE-219) | kangmin | Claude Code | 부분 수용 | 아님 | [기록](kangmin/2026-11-09-agent-token-k6-output-and-hook.md) |
| 2026-11-09 | 측정 모드를 AWS 담당 한 명이 아니라 측정자가 직접 켜고 끄고, 시작·끝 알림으로 겹침을 막기로 함 | kangmin | Claude Code | **거부** | 후보 | [기록](kangmin/2026-11-09-measure-mode-by-measurer.md) |
| 2026-11-09 | AWS 담당 동의로 ISE-216(서버 추첨 생성 + k6 ID 전달)을 강민이 넘겨받아 처리하기로 함 | kangmin | Claude Code | 수용 | 아님 | [기록](kangmin/2026-11-09-ise216-take-over-after-aws-owner-ok.md) |
| 2026-11-09 | L1~L3 ID 모드에서 일반 판매 회차를 SALE_SCHEDULE_ID로 나누고 회차 ID만 주면 실패하게 둠 | kangmin | Claude Code | 수용 | 아님 | [기록](kangmin/2026-11-09-k6-sale-schedule-id-split.md) |
| 2026-11-09 | 배포 측정에서 L1~L3 관리자 로그인 문제를 서버 추첨 생성 + k6 ID 전달(1안)로 풀고 AWS 담당 리뷰를 받기로 함(ISE-216) | kangmin | Claude Code | 수용 | 아님 | [기록](kangmin/2026-11-09-loadtest-lottery-seed-on-server.md) |
| 2026-11-09 | 자동결제 겹침 contract 이슈를 추천안 하나로 좁히지 않고 모든 선택지·트레이드오프를 담아 결제 담당과 고르기로 함 | kangmin | Claude Code | 부분 수용 | 후보 | [기록](kangmin/2026-11-09-billing-overlap-issue-all-options.md) |
| 2026-11-09 | 입장 토큰 만료 시점으로 결제 완료 대신 좌석 선점 성공 시점을 고름 | kangmin | Claude Code | 수용 | 후보 | [기록](kangmin/2026-11-09-entry-token-consume-on-hold.md) |
| 2026-11-09 | 부하 발생기 EC2 안내가 1인용 구성이라 팀 공용(팀원 IP·키, 각자 폴더, 한 번에 한 측정)으로 바꾸게 함 | kangmin | Claude Code | **거부** | 아님 | [기록](kangmin/2026-11-09-loadgen-shared-by-team.md) |
| 2026-11-08 | 커넥션 대기 1초 동의와 추첨 시나리오 L1~L3 검토 의견을 AI 초안 그대로 Linear에 올림 | kangmin | Claude Code | 수용 | 아님 | [기록](kangmin/2026-11-08-connection-timeout-and-lottery-scenario-review.md) |
| 2026-11-08 | 서버 측정을 11/13 재가동 뒤로 두자는 일정안 거부, 11/9에 서버를 켬 | kangmin | Claude Code | **거부** | 아님 | [기록](kangmin/2026-11-08-server-start-tomorrow.md) |
| 2026-11-09 | 오류 응답이 Accept에 따라 비는 결함을 상담 서비스 안이 아니라 공통 예외 처리기(common)에서 고치기로 함(ISE-221) | junhyun | Claude Code | 수용 | 아님 | [기록](junhyun/2026-11-09-error-response-json-in-common.md) |
| 2026-11-09 | 예매 문의 챗봇을 /help 페이지로 두자는 추천 대신 모든 화면에서 여는 떠 있는 위젯으로 바꾸기로 함 | junhyun | Claude Code | **거부** | 아님 | [기록](junhyun/2026-11-09-assistant-floating-widget.md) |
| 2026-11-09 | 홈 리뉴얼 시안 3종 중 AI 추천(A 배치 + C 로고) 대신 시안 B와 B 로고를 고르고, 라이트·다크 두 모드와 굵기·카드 비율 수정을 요청함 | junhyun | Claude Code | 부분 수용 | 아님 | [기록](junhyun/2026-11-09-home-renewal-draft-b-light-dark.md) |
| 2026-11-09 | Q1-d 로컬 확인에서 출발 대기를 다시 계산하는 방식으로 고치고, Q1 ↔ Q1-d 수치 비교는 배포 서버로 넘김 | youngeun | Claude Code | 수용 | 후보 | [기록](youngeun/2026-11-09-q1d-local-limit-and-clock-drift.md) |
| 2026-11-08 | 배포 측정 기반(ISE-191)을 M4로 두되 측정 전에 끝내고, 측정용 토큰은 측정 동안만 측정 전용 키로 바꿔 띄우기로 함 | junhyun | Claude Code | 수용 | 후보 | [기록](junhyun/2026-11-08-deploy-measure-token-key-swap.md) |
| 2026-11-09 | 토스 어댑터의 orderId를 토큰에서 만들고, 늦은 승인 방지는 Prevented를 내지 않으며, 빌링은 지원하지 않고, 배포 기본은 mock으로 한 추천안을 수용함 | junhyun | Claude Code | 수용 | 아님 | [기록](junhyun/2026-11-09-toss-adapter-order-id-and-late-approval.md) |
| 2026-11-09 | 결제 건별 슬랙 알림(ISE-183)의 세부 여섯 가지(결제 시각 표기, 금액 불일치 합치기, 해결 메시지 없음, 대사 10건 상한, 환불 문구)를 추천안대로 정함 | junhyun | Claude Code | 수용 | 아님 | [기록](junhyun/2026-11-09-payment-event-alert-details.md) |
| 2026-11-09 | TX1 커밋 응답 유실 대책으로 AI가 낸 "재시도가 REQUESTED를 이어받아 승인" 방안을 거부하고 한계를 ADR로 기록함(ISE-169) | junhyun | Claude Code | **거부** | 후보 | [기록](junhyun/2026-11-09-ise169-reject-retry-takeover.md) |
| 2026-11-09 | 백로그 설계안 중 AI 상담은 Claude API 키를 받아 Haiku 5.5로 시작하고, 토스 테스트 키는 수요일에 받고, 시연 장애 버튼은 Gateway 운영자 경로로 두기로 함 | junhyun | Claude Code | 부분 수용 | 아님 | [기록](junhyun/2026-11-09-backlog-design-decisions.md) |
| 2026-11-08 | 프론트 사용자가 API를 직접 쏘는 사람보다 대기열에 늦게 선다는 문제를 제기하고 AI 답(rate limit·사전 대기실 등)을 받았으나 기록만 남김 | junhyun | Claude Code | 보류 | 후보 | [기록](junhyun/2026-11-08-queue-fairness-direct-api-record-only.md) |
| 2026-11-08 | Linear 정리 계획 중 좌석 조회 일정은 두 담당이 합의하게 하고, 내 백로그 줄이기 제안은 거두고 11/13까지 모두 끝내기로 함 | junhyun | Claude Code | 부분 수용 | 아님 | [기록](junhyun/2026-11-08-linear-cleanup-plan-feedback.md) |
| 2026-11-06 | 선점 기준선 비교를 로컬에서 하자는 추천 대신 로컬은 사전 측정, 수치화는 배포 서버로 제시함 | kangmin | Claude Code | 부분 수용 | 아님 | [기록](kangmin/2026-11-06-ise25-final-comparison-on-deploy.md) |
| 2026-11-06 | K1 local 결과를 ISE-25 기준선으로 인정하자는 제안을 측정 대상이 다르다는 이유로 거부함 | kangmin | Claude Code | **거부** | 아님 | [기록](kangmin/2026-11-06-ise25-baseline-not-k1.md) |
| 2026-11-05 | 결제 작업 지표의 job 라벨 충돌을 규칙 수정이 아니라 태그를 task로 바꿔 풀기로 함 | kangmin | Claude Code | 수용 | 후보 | [기록](kangmin/2026-11-05-payment-job-label-task.md) |
| 2026-11-05 | MVP 시연: 리허설 전 `main` 동결 제안 거부, `dev` 머지와 로컬 테스트 뒤 반영 | kangmin | Claude Code | **거부** | 아님 | [기록](kangmin/2026-11-05-demo-main-merge-after-local-test.md) |
| 2026-11-05 | MVP 시연: 장애 현장 대체 제안 거부, 장애 상황도 함께 보임 | kangmin | Claude Code | **거부** | 후보 | [기록](kangmin/2026-11-05-demo-show-failures-live.md) |
| 2026-11-05 | MVP 시연: 추첨은 응모자를 적재해 실제처럼 모의 진행 | kangmin | Claude Code | 부분 수용 | 후보 | [기록](kangmin/2026-11-05-demo-lottery-seeded-entries.md) |
| 2026-11-05 | MVP 시연: 영역 담당별 진행 제안 거부, 통합 진행 | kangmin | Claude Code | **거부** | 아님 | [기록](kangmin/2026-11-05-demo-single-integrated-presenter.md) |
| 2026-11-06 | 좌석 조회 경량화(P4) 측정을 반효승 담당에서 일부 가져와 좌석 선점과 같은 환경(측정 브랜치)에서 재고, 본 구현은 ISE-189에 둠 | kwonwoo | Claude Code | 부분 수용 | 후보 | [기록](kwonwoo/2026-11-06-measure-p4-seat-map-in-own-environment.md) |
| 2026-11-05 | 시연 모드 Grafana 링크를 localhost:3000에 고정한 AI 설계를 거두고, 배포 서버 Grafana를 팀원 IP로 열어 화면을 연 호스트 기준으로 잇게 함 | junhyun | Claude Code | **거부** | 아님 | [기록](junhyun/2026-11-05-grafana-link-deploy-host.md) |
| 2026-11-05 | 배포 서버 앱의 GC는 리허설(11/6)까지 Serial로 두고, 다음 주 부하 측정 전에 G1을 명시하기로 함 | junhyun | Claude Code | 수용 | 아님 | [기록](junhyun/2026-11-05-deploy-g1-after-rehearsal.md) |
| 2026-11-05 | P4를 바로 시작하자는 제안 대신 G1 GC로 먼저 재 보고, 정상 결과가 나오면 기존 순서를, 안 나오면 P4를 먼저 하기로 함 | kwonwoo | Claude Code | 부분 수용 | 후보 | [기록](kwonwoo/2026-11-05-g1-first-then-decide-p4-order.md) |
| 2026-11-05 | 부하 테스트에 대비해 배포 EC2를 flex(m7i-flex.xlarge)에서 같은 크기의 일반 인스턴스(m7i.xlarge)로 바꿈 | junhyun | Claude Code | 수용 | 아님 | [기록](junhyun/2026-11-05-deploy-ec2-m7i-standard-for-load-test.md) |
| 2026-11-05 | 핵심 흐름만 보이던 AI의 화면 구성을 실제 서비스형 레이아웃·그림 에셋으로 바꾸게 함(ISE-187) | junhyun | Claude Code | 부분 수용 | 아님 | [기록](junhyun/2026-11-05-frontend-service-like-layout.md) |
| 2026-11-04 | **JUnit 부하 근사(`SeatHoldLoadTest`) 대신 k6로 부하 테스트를 다시 하기로 하고, 대기열 초당 100명 입장 기준 병목·단계별 개선 계획 작성** | kwonwoo | Claude Code | 거부 | 후보 | [기록](kwonwoo/2026-11-04-k6-over-junit-load-approximation.md) |
| 2026-11-04 | 지출 한도로 멈춘 배포·Jenkins 서버를 한도를 올려 살리되, 그 시점을 다음 날(11/5)로 미룸 | junhyun | Claude Code | 부분 수용 | 아님 | [기록](junhyun/2026-11-04-spend-limit-raise-tomorrow.md) |
| 2026-11-04 | ISE-150 리뷰 지적 11건을 이번 PR 수정, 담당자 확인, 후속으로 나눔 | kangmin | Claude Code | 수용 | 아님 | [기록](kangmin/2026-11-04-ise-150-review-triage.md) |
| 2026-11-04 | 배포는 EC2 한 대로 시작하고, 서버를 멈춘 기간(11/7~11/12)에 RDS 등 매니지드 서비스 분리를 검토하기로 함(ADR 0047) | junhyun | Claude Code | 수용 | 후보 | [기록](junhyun/2026-11-04-single-ec2-then-review-managed.md) |
| 2026-11-04 | 리허설 일정에 맞춰 측정 전에 배포 EC2를 16GB급으로 정하고 AWS Paid plan으로 바꿈 | junhyun | Claude Code | 수용 | 아님 | [기록](junhyun/2026-11-04-deploy-ec2-16gb-paid-plan.md) |
| 2026-11-04 | M3 배포 순서(173·178 → 176 → 174·177 → 175)를 받아들이고 프론트 nginx(ISE-109)를 M4에서 M3로 당겨 이미지 워크플로(ISE-173)와 함께 함 | junhyun | Claude Code | 부분 수용 | 아님 | [기록](junhyun/2026-11-04-m3-deploy-order-with-ise109.md) |
| 2026-11-04 | 배포용 AWS 계정 운영: 시드니 리전 유지(서울 전환 안 함), 팀원은 각자 SSH 키와 IP로 접속하고 콘솔 초대는 필요한 사람만 | junhyun | Claude Code | 수용 | 아님 | [기록](junhyun/2026-11-04-aws-region-and-team-access.md) |
| 2026-11-03 | ISE-79 동시성 시나리오(S0~S4, C1~C7, L1~L6)와 이슈 전제 검토를 수용하고 워크트리에서 단계별 실측 | kwonwoo | Claude Code | 수용 | 후보 | [기록](kwonwoo/2026-11-03-ise79-scenarios-measure-in-worktree.md) |
| 2026-11-03 | 배포·관측·부하 측정 설계: AI 추천 대부분 수용, Jenkins를 더하고 main 머지 자동 배포·오픈 피크 전체 시나리오를 고름, 요청 로그는 공통 필터로 | junhyun | Claude Code | 부분 수용 | 후보 | [기록](junhyun/2026-11-03-deploy-observability-loadtest-design.md) |
| 2026-11-03 | 반효승 PR #282(DLQ 처리기)·#283(대사 내부 조회 API) AI 리뷰를 PR 코멘트로 남기고, 대사 응답 모양은 #283에 맞춰 결제 쪽 어댑터를 고치기로 함 | junhyun | Claude Code, Opus 리뷰 | 수용 | 아님 | [기록](junhyun/2026-11-03-review-hyoseung-dlq-and-reconciliation-api.md) |
| 2026-11-03 | 대사 RC-5(예매 금액)·RC-6(사용자) 불일치를 APPROVED 결제로만 보도록 ADR 0029를 고침(환불하면 닫히는 경로) | junhyun | Claude Code, Opus 리뷰 | 수용 | 아님 | [기록](junhyun/2026-11-03-reconciliation-rc5-rc6-approved-only.md) |
| 2026-11-03 | 계층: Kafka 소비자·스케줄러를 infrastructure에 두자는 추천을 거부하고, 입구 어댑터를 presentation으로 모두 옮기기로 팀 회의에서 확정 | kangmin | Claude Code + Claude(대기열 담당) | **거부** | 후보 | [기록](kangmin/2026-11-03-inbound-adapters-to-presentation.md) |
| 2026-11-02 | 추첨 마일스톤: 팀 문서대로 M4에 둔 배치를 거부, 필수 기능으로 올려 기본 M2·통합 테스트 M3·측정·개선 M4(문서까지 확정) | kangmin | Claude Code | **거부** | 아님 | [기록](kangmin/2026-11-02-lottery-must-milestone.md) |
| 2026-11-02 | 프론트는 백엔드에 필드를 먼저 요청하지 않고 구현된 API만 쓰며, 빈 곳은 나중에 고칠 것으로 기록(ISE-113) | junhyun | Claude Code | **거부** | 아님 | [기록](junhyun/2026-11-02-frontend-follows-backend-as-is.md) |
| 2026-11-02 | 좌석·매수 반환 위치를 먼저 합의하자는 AI 권고 대신 만료 스케줄러(ISE-13)를 먼저 구현하고 논의 항목을 문서로 남김 | kwonwoo | Claude Code | 부분 수용 | 아님 | [기록](kwonwoo/2026-11-02-hold-expiry-implement-first.md) |
| 2026-11-02 | Gateway가 만료된 Access 토큰에 401 `TOKEN_EXPIRED`를 주도록 SA 11-3대로 고침(ISE-131) | junhyun | Claude Code | 수용 | 아님 | [기록](junhyun/2026-11-02-gateway-token-expired-code.md) |
| 2026-11-02 | 프론트 Refresh 토큰은 탭 단위 `sessionStorage`, Access 토큰은 메모리(ADR 0040 4절) | junhyun | Claude Code | 수용 | 아님 | [기록](junhyun/2026-11-02-refresh-token-session-storage.md) |
| 2026-11-02 | 프론트 디자인 시스템 확정: 이선좌 워드마크, 인디고 주 색, 좌석 상태 규칙(409 뒤 빠진 좌석만 풀기, 좌석 바뀌면 새 멱등키) | junhyun | Claude Code | 수용 | 아님 | [기록](junhyun/2026-11-02-design-system-sample-accepted.md) |
| 2026-11-02 | **팀 규칙 변경: 결정·논의·학습 기록은 Notion보다 저장소 `docs/`에 먼저 남기고, 어긋나면 저장소 기록을 따른다(`AGENTS.md` 대전제 4). AI가 개인 전역 지침에 넣은 것을 바로잡음** | junhyun | Claude Code | **거부** | 아님 | [기록](junhyun/2026-11-02-docs-first-as-team-rule.md) |
| 2026-11-02 | 프론트 결정은 Notion 결정 기록 #7을 고치지 않고 저장소 docs(ADR 0040, `docs/design/frontend/`)에 기록 | junhyun | Claude Code | **거부** | 아님 | [기록](junhyun/2026-11-02-records-in-docs-over-notion.md) |
| 2026-11-02 | 프론트·Gateway 연결은 BFF 대신 nginx 중계. Gateway에 CORS를 넣지 않고 백엔드 계약도 그대로 | junhyun | Claude Code | 수용 | 아님 | [기록](junhyun/2026-11-02-frontend-relay-over-bff.md) |
| 2026-11-02 | 프론트 스택: 팀 결정 #7(정적 HTML·JS)의 바꿀 조건을 근거로 저장소 안 `frontend/`의 React SPA로 전환, Spring Boot 내장은 하지 않음 (ADR 0040) | junhyun | Claude Code | 수용 | 아님 | [기록](junhyun/2026-11-02-frontend-react-spa.md) |
| 2026-11-01 | 계약 ② 확정·머지 브랜치 삭제: 예매 흐름 담당 답과 소유자 동의를 기다리자는 추천 대신 바로 진행(문서에 확인 없이 확정했다고 명시) | kangmin | Claude Code | **거부** | 후보 | [기록](kangmin/2026-11-01-contract-confirm-without-consumer-check.md) |
| 2026-10-31 | 추첨 D6 변경: 자동결제 전용 그룹(동시 3)을 두지 않고 `pg-approve`(동시 10)를 함께 씀. 겹침 문제는 시나리오 2 트러블슈팅 소재 | kangmin | Claude Code | **거부** | 후보 | [기록](kangmin/2026-10-31-lottery-billing-share-approve-group.md) |
| 2026-10-31 | 추첨 SA 반영: 후속 분리는 수용, 다른 담당 동의가 필요한 항목은 합의 대기 대신 선반영 후 보고 | kangmin | Claude Code | 부분 수용 | 아님 | [기록](kangmin/2026-10-31-lottery-sa-sync-apply-first-report-later.md) |
| 2026-10-31 | 추첨 이슈화: 다른 서비스·모듈 일거리를 각 담당 배치 대신 추첨 담당이 묶어 맡음(담당은 합의·리뷰) | kangmin | Claude Code | **거부** | 아님 | [기록](kangmin/2026-10-31-lottery-cross-area-work-ownership.md) |
| 2026-10-31 | 추첨 D9 후속: 설정은 일반 오픈·응모 시작 전만, 좌석 캐시는 DB 조건에 맡김, 알림은 결과 조회 API 기본 | kangmin | Claude Code | 수용 | 아님 | [기록](kangmin/2026-10-31-lottery-seat-split-followups.md) |
| 2026-10-30 | **ISE-11 구현이 다른 담당 모듈에 걸친 부분을 contract 이슈로 알리고 유지하자는 AI 권고를 거부하고 선점 모듈 안으로 격리** | kwonwoo | Claude Code | 거부 | 아님 | [기록](kwonwoo/2026-10-30-seathold-isolate-other-owners.md) |
| 2026-10-30 | 포트폴리오 트러블슈팅 서사 확보를 위해 좌석 선점 동시성·데드락 방어와 대사 쿼리를 MVP 이후 고도화(M4)로 분리 (ADR 0035) | kwonwoo | Antigravity (Gemini) | 수용 | 후보 | [기록](kwonwoo/2026-10-30-mvp-and-troubleshooting-scope-split.md) |
| 2026-10-30 | 트래픽 집중 지점 격리와 향후 스케일업을 위해 좌석 선점(seathold)을 별도 도메인 모듈로 분리하되 단방향 의존 규칙 수용 (ADR 0034) | kwonwoo | Antigravity (Gemini) | 부분 수용 | 후보 | [기록](kwonwoo/2026-10-30-seathold-module-separation.md) |
| 2026-10-30 | 추첨 D12: `NOT_SENT`는 새 예매로 1회 재시도, 결과 미수신 만료는 같은 예매 1회 연장(대안: 즉시 승계, 원인 확인 후 분기 등), ADR 0026 유지 | kangmin | Claude Code | 수용 | 후보 | [기록](kangmin/2026-10-30-lottery-payment-failure-handling.md) |
| 2026-10-30 | 추첨 D11: 결제 결과는 기존 예매 소비자에서 같은 트랜잭션으로 처리(A)하고, 서비스 분리 대비 모듈 사이 호출은 포트로만 | kangmin | Claude Code | 수용 | 후보 | [기록](kangmin/2026-10-30-lottery-payment-result-module-port.md) |
| 2026-10-30 | 추첨 시간대 정책: 사용자 제안(새벽 추첨 + 예매 차단)을 AI가 나눠, 저트래픽 시간대 추첨은 기본 정책, 예매 차단은 측정 뒤 최후 수단으로 | kangmin | Claude Code | 수용 | 후보 | [기록](kangmin/2026-10-30-lottery-draw-time-window-policy.md) |
| 2026-10-30 | 추첨 D6: 요구 조건 3개 + 전용 Bulkhead `pg-billing` 동시 3·전용 토픽·백프레셔·그룹별 CB를 결제 담당에게 제안 | kangmin | Claude Code | 수용 | 후보 | [기록](kangmin/2026-10-30-lottery-billing-bulkhead-proposal.md) |
| 2026-10-30 | 추첨 D4: 응모 때 빌링키 확인 없이 결제 시점 실패 → 승계 + 사전 안내, 이벤트 사본은 확장안 | kangmin | Claude Code | 수용 | 아님 | [기록](kangmin/2026-10-30-lottery-billing-key-check-at-payment.md) |
| 2026-10-30 | 추첨 D14: 결과 미확정(NMC) 결제는 선점 만료로 끊고 늦은 승인은 E8 환불, 종결 최장 추첨 후 60분 | kangmin | Claude Code | 수용 | 아님 | [기록](kangmin/2026-10-30-lottery-close-on-hold-expiry.md) |
| 2026-10-30 | 추첨 D13: 소비자 안 승계(A) 대신 실패는 FAILED만 기록하고 1초 승계 워커가 추첨 행 잠금으로 승계(D) | kangmin | Claude Code | 수용 | 후보 | [기록](kangmin/2026-10-30-lottery-succession-worker.md) |
| 2026-10-30 | 추첨 D8: 한 트랜잭션(A) + 쓰기 줄이기·시간 상한·측정, 초과 시 청크(B) | kangmin | Claude Code | 수용 | 후보 | [기록](kangmin/2026-10-30-lottery-draw-single-transaction.md) |
| 2026-10-30 | 추첨 D7: 해시 정렬(C)은 수용, 시드 약속은 확장안으로 미루고 규칙 공개 + 1계정 중복 차단으로 마무리 | kangmin | Claude Code | 부분 수용 | 후보 | [기록](kangmin/2026-10-30-lottery-fairness-scope.md) |
| 2026-10-30 | 추첨 응모 매수: 1인 1매 추천 대신 SA대로 1~2매 유지 | kangmin | Claude Code | **거부** | 아님 | [기록](kangmin/2026-10-30-lottery-entry-quantity-keep-two.md) |
| 2026-10-30 | 추첨 D5: 재응모 허용(A) 추천 대신 마감 전 취소는 받고 같은 회차 재응모는 막기, 재응모는 확장안 | kangmin | Claude Code | **거부** | 아님 | [기록](kangmin/2026-10-30-lottery-cancel-no-reentry.md) |
| 2026-10-29 | 입장 토큰 회차 비교: 선점 경로를 /schedules/{scheduleId}/reservations로 바꾸고 Gateway가 경로로 비교 (계약 ①) | youngeun | Claude (Cowork) | 수용 | 아님 | [기록](youngeun/2026-10-29-entry-token-schedule-check-path.md) |
| 2026-10-29 | 대기열 설계 설명 중 AI 근거 두 가지(멱등성, issued 선기록의 자동 복구)를 틀렸다고 지적, 만료 기록 처리 추가 | youngeun | Claude (Cowork) | **거부** | 후보 | [기록](youngeun/2026-10-29-queue-ai-reasoning-corrections.md) |
| 2026-10-29 | 대기열: 토큰 API 분리, READY 상태, 진입·순번 조회 같은 상태 응답, 순번 조회 읽기 전용, jti 계산 (ADR 0031) | youngeun | Claude (Cowork) | 부분 수용 | 후보 | [기록](youngeun/2026-10-29-queue-token-api-ready.md) |
| 2026-10-29 | 좌석 선점 결과 계약(ISE-21): 동기형 멱등 재요청 처리 규칙(실패 시 롤백·동시 PK 충돌 재조회) 수용 및 응답에 `seatIds` 포함 결정 | kwonwoo | Antigravity (Gemini) | 수용 | 후보 | [기록](kwonwoo/2026-10-29-seat-hold-contract-and-idempotency.md) |
| 2026-10-29 | 추첨 D9: 추첨석 미리 떼지 않음·구분 컬럼 추천 대신 S석 일부를 좌석 등급 `LOTTERY`로 떼기(관리자가 한 등급+좌석 수, 응모 시작 전 수정) | kangmin | Claude Code | **거부** | 후보 | [기록](kangmin/2026-10-29-lottery-seat-allocation-grade.md) |
| 2026-10-29 | 추첨 D3: 응모 접수는 동기 INSERT 기준선으로 시작, 측정 시나리오 1로 접수형과 비교 | kangmin | Claude Code | 수용 | 후보 | [기록](kangmin/2026-10-29-lottery-entry-sync-insert-baseline.md) |
| 2026-10-29 | 추첨 D2: "1인 1회차 1응모" 규칙 유지, 좌석 등급별 복수 응모 금지(구현은 추천 A, 팀 확인 전) | kangmin | Claude Code | 수용 | 아님 | [기록](kangmin/2026-10-29-lottery-one-entry-per-schedule.md) |
| 2026-10-28 | 추첨 방향: 추첨 유지 + 측정 시나리오 3종(마감 몰림, 자동결제 폭주, 일반 예매 간섭) | kangmin | Claude Code | 수용 | 후보 | [기록](kangmin/2026-10-28-lottery-direction-measurement.md) |
| 2026-10-28 | Notion SA 반영 방법: 구간 교체 HTML 대신 v3.3 전체를 가져오기로 교체 | kangmin | Claude Code | **거부** | 아님 | [기록](kangmin/2026-10-28-notion-sa-full-import.md) |
| 2026-10-28 | 테이블 이름 복수형 적용 범위: 결제 코드·문서는 후속 이슈(ISE-67), `outbox_events`, M3 테이블 제외 | kangmin | Claude Code | 수용 | 아님 | [기록](kangmin/2026-10-28-table-naming-scope.md) |
| 2026-10-28 | Notion SA 동기화: 변경마다 바로 고치는 AI 방식 대신 쌓아서 한 번에 반영 | kangmin | Claude Code | **거부** | 아님 | [기록](kangmin/2026-10-28-notion-sa-batch-sync.md) |
| 2026-10-28 | 역할 변경 확정: 결제 담당이 Mock PG까지 맡고 지원 불필요, SA·마일스톤 담당 표기 정정 | kangmin (결제 담당 확인 전달) | Claude Code | 수용 | 아님 | [기록](kangmin/2026-10-28-role-change-confirmed.md) |
| 2026-10-30 | Mock PG 설계 Opus 리뷰 지적 전부 반영, 계약 테스트는 bootJar 별도 프로세스, 처리 중 승인 재요청은 409, compose 프로필은 팀 확인 대기 | junhyun | Claude Code, Opus 리뷰 | 수용 | 후보 | [기록](junhyun/2026-10-30-mock-pg-design-review.md) |
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
