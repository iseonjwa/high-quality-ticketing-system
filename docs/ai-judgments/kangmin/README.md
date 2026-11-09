# kangmin AI 판단 기록

kangmin이 AI 응답·제안에 내린 판단이다. 작성 절차는 [`ai-judgment` 스킬](../../../.agents/skills/ai-judgment/SKILL.md)을 따른다. 최신이 위로 온다. `공통`이 `예`인 기록은 [팀 공통 판단](../README.md#팀-공통-판단)에도 올라간다.

## 개별 기록

거부 판단은 굵게 표시한다.

| 날짜 | 판단 대상 | AI | 판단 | 공통 | 발표 후보 | 기록 |
|---|---|---|---|---|---|---|
| 2026-11-09 | 측정 전 main 반영에 팀원 PR을 넣을지 묻자는 제안을 두고, 팀원 PR은 측정하지 않을 때 상황을 봐서 넘기기로 함 | Claude Code | **거부** | 예 | 아님 | [기록](2026-11-09-team-prs-not-in-freeze-merge.md) |
| 2026-11-09 | 측정 모드 알림을 성능-모니터링 채널에 두지 않고 별도 채널로 옮기고, 배포 알림처럼 꾸미기로 함 | Claude Code | **거부** | 예 | 아님 | [기록](2026-11-09-measure-mode-alert-own-channel.md) |
| 2026-11-09 | dev→main을 측정 전 한 번에 넘기는 대신, 시험용으로 먼저 넘기고 이후에 다시 넘기기로 함 | Claude Code | 부분 수용 | 예 | 아님 | [기록](2026-11-09-main-merge-test-first.md) |
| 2026-11-09 | 측정 모드를 켜고 끌 때 슬랙 자동 알림을 붙이기로 함(사람 알림만으로 두지 않음) | Claude Code | 수용 | 예 | 아님 | [기록](2026-11-09-measure-mode-slack-alert.md) |
| 2026-11-09 | 기록 검사 훅 속도 개선(약 25배)을 변이 31가지 비교로 검증한 뒤 올리고, 한글 경로 표시 개선을 함께 넣음 | Claude Code | 수용 | 예 | 후보 | [기록](2026-11-09-records-check-25x-faster.md) |
| 2026-11-09 | 이슈·PR·코멘트를 평서체 지시형으로 쓴 것을 건방지다고 지적하고 공손한 합니다체로 바꾸게 함 | Claude Code | **거부** | 아니오 | 아님 | [기록](2026-11-09-polite-tone-for-issues-and-prs.md) |
| 2026-11-09 | 에이전트 k6 실행 토큰 과다(진행 표시줄 출력)와 느린 기록 검사 훅 진단을 중 k6 출력 건만 이슈로 올리기로 함(ISE-219) | Claude Code | 부분 수용 | 예 | 아님 | [기록](2026-11-09-agent-token-k6-output-and-hook.md) |
| 2026-11-09 | 측정 모드를 AWS 담당 한 명이 아니라 측정자가 직접 켜고 끄고, 시작·끝 알림으로 겹침을 막기로 함 | Claude Code | **거부** | 예 | 후보 | [기록](2026-11-09-measure-mode-by-measurer.md) |
| 2026-11-09 | AWS 담당 동의로 ISE-216(서버 추첨 생성 + k6 ID 전달)을 강민이 넘겨받아 처리하기로 함 | Claude Code | 수용 | 예 | 아님 | [기록](2026-11-09-ise216-take-over-after-aws-owner-ok.md) |
| 2026-11-09 | L1~L3 ID 모드에서 일반 판매 회차를 SALE_SCHEDULE_ID로 나누고 회차 ID만 주면 실패하게 둠 | Claude Code | 수용 | 예 | 아님 | [기록](2026-11-09-k6-sale-schedule-id-split.md) |
| 2026-11-09 | herdr 지휘자 운영을 잠시 멈췄다가, 판단은 앱·실행은 herdr 지휘자로 나눠 다시 쓰기로 함 | Claude Code | 부분 수용 | 아니오 | 아님 | [기록](2026-11-09-herdr-conductor-hold-one-at-a-time.md) |
| 2026-11-09 | 배포 측정에서 L1~L3 관리자 로그인 문제를 서버 추첨 생성 + k6 ID 전달(1안)로 풀고 AWS 담당 리뷰를 받기로 함(ISE-216) | Claude Code | 수용 | 예 | 아님 | [기록](2026-11-09-loadtest-lottery-seed-on-server.md) |
| 2026-11-09 | 자동결제 겹침 contract 이슈를 추천안 하나로 좁히지 않고 모든 선택지·트레이드오프를 담아 결제 담당과 고르기로 함 | Claude Code | 부분 수용 | 예 | 후보 | [기록](2026-11-09-billing-overlap-issue-all-options.md) |
| 2026-11-09 | 입장 토큰 만료 시점으로 결제 완료 대신 좌석 선점 성공 시점을 고름 | Claude Code | 수용 | 예 | 후보 | [기록](2026-11-09-entry-token-consume-on-hold.md) |
| 2026-11-09 | 부하 발생기 EC2 안내가 1인용 구성이라 팀 공용(팀원 IP·키, 각자 폴더, 한 번에 한 측정)으로 바꾸게 함 | Claude Code | **거부** | 예 | 아님 | [기록](2026-11-09-loadgen-shared-by-team.md) |
| 2026-11-09 | 위클리 스크럼 "지난주 목표 및 일정" 초안이 작업 나열이라 주간 리뷰에 맞지 않는다고 지적함 | Claude Code | **거부** | 아니오 | 아님 | [기록](2026-11-09-weekly-scrum-last-week-section.md) |
| 2026-11-08 | 커넥션 대기 1초 동의와 추첨 시나리오 L1~L3 검토 의견을 AI 초안 그대로 Linear에 올림 | Claude Code | 수용 | 예 | 아님 | [기록](2026-11-08-connection-timeout-and-lottery-scenario-review.md) |
| 2026-11-08 | 서버 측정을 11/13 재가동 뒤로 두자는 일정안 거부, 11/9에 서버를 켬 | Claude Code | **거부** | 예 | 아님 | [기록](2026-11-08-server-start-tomorrow.md) |
| 2026-11-06 | EC2 종료 동작 설명이 틀린 것 아니냐고 지적했고, 사실 오류가 아니라 용어 혼동으로 정리함 | Claude Code | **거부** | 아니오 | 아님 | [기록](2026-11-06-ec2-stop-vs-terminate-wording.md) |
| 2026-11-06 | 선점 기준선 비교를 로컬에서 하자는 추천 대신 로컬은 사전 측정, 수치화는 배포 서버로 제시함 | Claude Code | 부분 수용 | 예 | 아님 | [기록](2026-11-06-ise25-final-comparison-on-deploy.md) |
| 2026-11-06 | K1 local 결과를 ISE-25 기준선으로 인정하자는 제안을 측정 대상이 다르다는 이유로 거부함 | Claude Code | **거부** | 예 | 아님 | [기록](2026-11-06-ise25-baseline-not-k1.md) |
| 2026-11-05 | 결제 작업 지표의 job 라벨 충돌을 규칙 수정이 아니라 태그를 task로 바꿔 풀기로 함 | Claude Code | 수용 | 예 | 후보 | [기록](2026-11-05-payment-job-label-task.md) |
| 2026-11-05 | herdr 병렬 작업을 시연 뒤로 미루자는 제안을 거부하고 지금 전부 처리하기로 함 | Claude Code | **거부** | 아니오 | 아님 | [기록](2026-11-05-demo-parallel-herdr-now.md) |
| 2026-11-05 | MVP 시연: 리허설 전 `main` 동결 제안 거부, `dev` 머지와 로컬 테스트 뒤 반영 | Claude Code | **거부** | 예 | 아님 | [기록](2026-11-05-demo-main-merge-after-local-test.md) |
| 2026-11-05 | MVP 시연: 장애 현장 대체 제안 거부, 장애 상황도 함께 보임 | Claude Code | **거부** | 예 | 후보 | [기록](2026-11-05-demo-show-failures-live.md) |
| 2026-11-05 | MVP 시연: 추첨은 응모자를 적재해 실제처럼 모의 진행 | Claude Code | 부분 수용 | 예 | 후보 | [기록](2026-11-05-demo-lottery-seeded-entries.md) |
| 2026-11-05 | MVP 시연: 영역 담당별 진행 제안 거부, 통합 진행 | Claude Code | **거부** | 예 | 아님 | [기록](2026-11-05-demo-single-integrated-presenter.md) |
| 2026-11-05 | 패키지 분리 PR의 base를 ISE-184 브랜치로 둔 구성을 거부하고 `dev`로 다시 올림 | Claude Code | **거부** | 아니오 | 아님 | [기록](2026-11-05-stacked-pr-base-to-dev.md) |
| 2026-11-05 | 추첨 응모 취소의 변수 `canceled`를 `canceledNow`로 바꾸자는 제안 거부 | Claude Code | **거부** | 아니오 | 아님 | [기록](2026-11-05-keep-canceled-name.md) |
| 2026-11-04 | 초기 구성 브랜치 `ggangmy/chore/init-env`를 남겨 두자는 추천을 거부하고 원격에서 삭제 | Claude Code | **거부** | 아니오 | 아님 | [기록](2026-11-04-delete-init-env-branch.md) |
| 2026-11-04 | ISE-150 리뷰 지적 11건을 이번 PR 수정, 담당자 확인, 후속으로 나눔 | Claude Code | 수용 | 예 | 아님 | [기록](2026-11-04-ise-150-review-triage.md) |
| 2026-11-04 | AWS 요금 절약 정보: Notion 데이터베이스 제안 대신 일반 페이지로 정함 | Claude(claude.ai) | **거부** | 아니오 | 아님 | [기록](2026-11-04-aws-cost-notes-notion-page.md) |
| 2026-11-03 | 회의 자료·판단 기록의 "입구" 표현을 "인바운드 어댑터"로 바꾸자는 제안 거부 | Claude Code | **거부** | 아니오 | 아님 | [기록](2026-11-03-keep-entrance-wording.md) |
| 2026-11-03 | 계층: Kafka 소비자·스케줄러를 infrastructure에 두자는 추천을 거부하고, 입구 어댑터를 presentation으로 모두 옮기기로 팀 회의에서 확정 | Claude Code + Claude(대기열 담당) | **거부** | 예 | 후보 | [기록](2026-11-03-inbound-adapters-to-presentation.md) |
| 2026-11-02 | 추첨 마일스톤: 팀 문서대로 M4에 둔 배치를 거부, 필수 기능으로 올려 기본 M2·통합 테스트 M3·측정·개선 M4(문서까지 확정) | Claude Code | **거부** | 예 | 아님 | [기록](2026-11-02-lottery-must-milestone.md) |
| 2026-11-01 | 계약 ② 확정·머지 브랜치 삭제: 예매 흐름 담당 답과 소유자 동의를 기다리자는 추천 대신 바로 진행(문서에 확인 없이 확정했다고 명시) | Claude Code | **거부** | 예 | 후보 | [기록](2026-11-01-contract-confirm-without-consumer-check.md) |
| 2026-11-01 | PR CI 자동 수정: 끄고 직접 확인하자는 추천 대신 켬 | Claude Code | **거부** | 아니오 | 아님 | [기록](2026-11-01-pr-ci-auto-fix-on.md) |
| 2026-10-31 | 추첨 D6 변경: 자동결제 전용 그룹(동시 3)을 두지 않고 `pg-approve`(동시 10)를 함께 씀. 겹침 문제는 시나리오 2 트러블슈팅 소재 | Claude Code | **거부** | 예 | 후보 | [기록](2026-10-31-lottery-billing-share-approve-group.md) |
| 2026-10-31 | 추첨 SA 반영: 후속 분리는 수용, 다른 담당 동의가 필요한 항목은 합의 대기 대신 선반영 후 보고 | Claude Code | 부분 수용 | 예 | 아님 | [기록](2026-10-31-lottery-sa-sync-apply-first-report-later.md) |
| 2026-10-31 | 추첨 이슈화: 다른 서비스·모듈 일거리를 각 담당 배치 대신 추첨 담당이 묶어 맡음(담당은 합의·리뷰) | Claude Code | **거부** | 예 | 아님 | [기록](2026-10-31-lottery-cross-area-work-ownership.md) |
| 2026-10-31 | 추첨 D9 후속: 설정은 일반 오픈·응모 시작 전만, 좌석 캐시는 DB 조건에 맡김, 알림은 결과 조회 API 기본 | Claude Code | 수용 | 예 | 아님 | [기록](2026-10-31-lottery-seat-split-followups.md) |
| 2026-10-30 | 추첨 D12: `NOT_SENT`는 새 예매로 1회 재시도, 결과 미수신 만료는 같은 예매 1회 연장(대안: 즉시 승계, 원인 확인 후 분기 등), ADR 0026 유지 | Claude Code | 수용 | 예 | 후보 | [기록](2026-10-30-lottery-payment-failure-handling.md) |
| 2026-10-30 | 추첨 D11: 결제 결과는 기존 예매 소비자에서 같은 트랜잭션으로 처리(A)하고, 서비스 분리 대비 모듈 사이 호출은 포트로만 | Claude Code | 수용 | 예 | 후보 | [기록](2026-10-30-lottery-payment-result-module-port.md) |
| 2026-10-30 | 추첨 시간대 정책: 사용자 제안(새벽 추첨 + 예매 차단)을 AI가 나눠, 저트래픽 시간대 추첨은 기본 정책, 예매 차단은 측정 뒤 최후 수단으로 | Claude Code | 수용 | 예 | 후보 | [기록](2026-10-30-lottery-draw-time-window-policy.md) |
| 2026-10-30 | 추첨 D6: 요구 조건 3개 + 전용 Bulkhead `pg-billing` 동시 3·전용 토픽·백프레셔·그룹별 CB를 결제 담당에게 제안 | Claude Code | 수용 | 예 | 후보 | [기록](2026-10-30-lottery-billing-bulkhead-proposal.md) |
| 2026-10-30 | 추첨 D4: 응모 때 빌링키 확인 없이 결제 시점 실패 → 승계 + 사전 안내, 이벤트 사본은 확장안 | Claude Code | 수용 | 예 | 아님 | [기록](2026-10-30-lottery-billing-key-check-at-payment.md) |
| 2026-10-30 | 추첨 D14: 결과 미확정(NMC) 결제는 선점 만료로 끊고 늦은 승인은 E8 환불, 종결 최장 추첨 후 60분 | Claude Code | 수용 | 예 | 아님 | [기록](2026-10-30-lottery-close-on-hold-expiry.md) |
| 2026-10-30 | 추첨 D13: 소비자 안 승계(A) 대신 실패는 FAILED만 기록하고 1초 승계 워커가 추첨 행 잠금으로 승계(D) | Claude Code | 수용 | 예 | 후보 | [기록](2026-10-30-lottery-succession-worker.md) |
| 2026-10-30 | 추첨 D8: 한 트랜잭션(A) + 쓰기 줄이기·시간 상한·측정, 초과 시 청크(B) | Claude Code | 수용 | 예 | 후보 | [기록](2026-10-30-lottery-draw-single-transaction.md) |
| 2026-10-30 | 추첨 D7: 해시 정렬(C)은 수용, 시드 약속은 확장안으로 미루고 규칙 공개 + 1계정 중복 차단으로 마무리 | Claude Code | 부분 수용 | 예 | 후보 | [기록](2026-10-30-lottery-fairness-scope.md) |
| 2026-10-30 | 추첨 응모 매수: 1인 1매 추천 대신 SA대로 1~2매 유지 | Claude Code | **거부** | 예 | 아님 | [기록](2026-10-30-lottery-entry-quantity-keep-two.md) |
| 2026-10-30 | 추첨 D5: 재응모 허용(A) 추천 대신 마감 전 취소는 받고 같은 회차 재응모는 막기, 재응모는 확장안 | Claude Code | **거부** | 예 | 아님 | [기록](2026-10-30-lottery-cancel-no-reentry.md) |
| 2026-10-29 | 추첨 D9: 추첨석 미리 떼지 않음·구분 컬럼 추천 대신 S석 일부를 좌석 등급 `LOTTERY`로 떼기(관리자가 한 등급+좌석 수, 응모 시작 전 수정) | Claude Code | **거부** | 예 | 후보 | [기록](2026-10-29-lottery-seat-allocation-grade.md) |
| 2026-10-29 | 추첨 D3: 응모 접수는 동기 INSERT 기준선으로 시작, 측정 시나리오 1로 접수형과 비교 | Claude Code | 수용 | 예 | 후보 | [기록](2026-10-29-lottery-entry-sync-insert-baseline.md) |
| 2026-10-29 | 추첨 D2: "1인 1회차 1응모" 규칙 유지, 좌석 등급별 복수 응모 금지(구현은 추천 A, 팀 확인 전) | Claude Code | 수용 | 예 | 아님 | [기록](2026-10-29-lottery-one-entry-per-schedule.md) |
| 2026-10-28 | 추첨 방향: 추첨 유지 + 측정 시나리오 3종(마감 몰림, 자동결제 폭주, 일반 예매 간섭) | Claude Code | 수용 | 예 | 후보 | [기록](2026-10-28-lottery-direction-measurement.md) |
| 2026-10-28 | Notion SA 반영 방법: 구간 교체 HTML 대신 v3.3 전체를 가져오기로 교체 | Claude Code | **거부** | 예 | 아님 | [기록](2026-10-28-notion-sa-full-import.md) |
| 2026-10-28 | 테이블 이름 복수형 적용 범위: 결제 코드·문서는 후속 이슈, `outbox_events`, M3 테이블 제외 | Claude Code | 수용 | 예 | 아님 | [기록](2026-10-28-table-naming-scope.md) |
| 2026-10-28 | Notion SA 동기화: 변경마다 바로 고치는 AI 방식 대신 쌓아서 한 번에 반영 | Claude Code | **거부** | 예 | 아님 | [기록](2026-10-28-notion-sa-batch-sync.md) |
| 2026-10-28 | 역할 변경 확정: 결제 담당이 Mock PG까지 맡고 지원 불필요, SA·마일스톤 담당 표기 정정 | Claude Code | 수용 | 예 | 아님 | [기록](2026-10-28-role-change-confirmed.md) |
| 2026-10-27 | 추첨 설계 포인트: 수치화된 트래픽 트러블슈팅이 약하다고 지적 | Claude Code | 부분 수용 | 아니오 | 아님 | [기록](2026-10-27-lottery-measurability-concern.md) |
| 2026-10-27 | AI 설계 검증 #7: 계층형 아키텍처 제안 7건 부분 수용, 응답 레코드 분리 거부 | Claude Code | 부분 수용 | 예 | 후보 | [기록](2026-10-27-layered-architecture-check7.md) |
| 2026-10-27 | Notion SA: AI의 블록 7곳 수정 대신 v3.2 통째 재등록 | Claude Code | **거부** | 예 | 아님 | [기록](2026-10-27-sa-v3-2-reregister.md) |
| 2026-10-27 | 회의 결정 후속: AI가 나눈 후속 이슈 대신 이번 통합에서 끝내기로 함 | Claude Code | **거부** | 예 | 아님 | [기록](2026-10-27-meeting-followups-finish-now.md) |
| 2026-10-27 | 통합 회의: 결정 시트 추천 중 D6 공통 모듈(하나+패키지), 리뷰 없음, 패키지 com.iseonjwa로 정함 | Claude Code | 부분 수용 | 예 | 후보 | [기록](2026-10-27-integration-meeting-decisions.md) |
| 2026-10-27 | 브랜치 통합: AI 추천(준현 브랜치 기준, Groovy, WebFlux 등 D1~D11)을 회의 입장으로 수용 | Claude Code | 수용 | 예 | 후보 | [기록](2026-10-27-branch-integration-recommendation.md) |
| 2026-10-26 | Config 서버: AI의 '없이 .env + 프로필' 추천 대신 Config 서버를 둠 | Claude Code, codex | **거부** | 예 | 아님 | [기록](2026-10-26-config-server.md) |
| 2026-10-26 | 기술 스택: AI 추천 Java 25 대신 Java 21(팀 합의 전), Boot 4.1·Spring Cloud 2025.1 유지 | Claude Code | 부분 수용 | 예 | 아님 | [기록](2026-10-26-tech-stack-java21.md) |
| 2026-10-26 | 계층 구조: AI 추천 4계층을 팀이 확정 | Claude Code | 수용 | 예 | 아님 | [기록](2026-10-26-layered-architecture-four-layers.md) |
| 2026-10-26 | 결정 기록 운영: 기존 항목 이력 덧붙이기 대신 대체됨 + 새 항목 | Claude Code | **거부** | 예 | 아님 | [기록](2026-10-26-decision-record-supersede.md) |
| 2026-10-26 | 서비스 구성: 팀이 3개로 확정, 대기열·추첨·알림은 모듈로 두고 근거가 쌓이면 분리 | Claude Code, codex | 수용 | 예 | 아님 | [기록](2026-10-26-service-composition-three-modules.md) |
| 2026-10-26 | 역할 분담: AI의 재검토 제안 대신 결정된 것으로 봄 | Claude Code | **거부** | 예 | 아님 | [기록](2026-10-26-role-assignment-not-reopen.md) |
| 2026-10-26 | API 응답 형식: AI의 기존 유지·6키 통일 대신 성공·실패 다른 형태 + traceId | Claude Code | 부분 수용 | 예 | 후보 | [기록](2026-10-26-api-response-single-envelope.md) |
| 2026-10-26 | 대기열 정렬 기준: 팀원 지적(진입 시각 동점)을 검증하고 순번 카운터(seq) 선택 | Claude Code | 수용 | 예 | 후보 | [기록](2026-10-26-queue-order-seq-counter.md) |

## 일지

작은 판단을 하루 한 파일에 한 줄씩 남긴다.

| 날짜 | 일지 |
|---|---|
| 2026-11-09 | [daily/2026-11-09.md](daily/2026-11-09.md) |
| 2026-11-08 | [daily/2026-11-08.md](daily/2026-11-08.md) |
| 2026-11-06 | [daily/2026-11-06.md](daily/2026-11-06.md) |
| 2026-11-05 | [daily/2026-11-05.md](daily/2026-11-05.md) |
| 2026-11-04 | [daily/2026-11-04.md](daily/2026-11-04.md) |
| 2026-11-02 | [daily/2026-11-02.md](daily/2026-11-02.md) |
| 2026-11-03 | [daily/2026-11-03.md](daily/2026-11-03.md) |
| 2026-11-01 | [daily/2026-11-01.md](daily/2026-11-01.md) |
| 2026-10-31 | [daily/2026-10-31.md](daily/2026-10-31.md) |
| 2026-10-30 | [daily/2026-10-30.md](daily/2026-10-30.md) |
| 2026-10-29 | [daily/2026-10-29.md](daily/2026-10-29.md) |
| 2026-10-28 | [daily/2026-10-28.md](daily/2026-10-28.md) |
| 2026-10-27 | [daily/2026-10-27.md](daily/2026-10-27.md) |
| 2026-10-26 | [daily/2026-10-26.md](daily/2026-10-26.md) |
