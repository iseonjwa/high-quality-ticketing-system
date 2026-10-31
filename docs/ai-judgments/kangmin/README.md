# kangmin AI 판단 기록

kangmin이 AI 응답·제안에 내린 판단이다. 작성 절차는 [`ai-judgment` 스킬](../../../.agents/skills/ai-judgment/SKILL.md)을 따른다. 최신이 위로 온다. `공통`이 `예`인 기록은 [팀 공통 판단](../README.md#팀-공통-판단)에도 올라간다.

## 개별 기록

거부 판단은 굵게 표시한다.

| 날짜 | 판단 대상 | AI | 판단 | 공통 | 발표 후보 | 기록 |
|---|---|---|---|---|---|---|
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
| 2026-10-31 | [daily/2026-10-31.md](daily/2026-10-31.md) |
| 2026-10-30 | [daily/2026-10-30.md](daily/2026-10-30.md) |
| 2026-10-29 | [daily/2026-10-29.md](daily/2026-10-29.md) |
| 2026-10-28 | [daily/2026-10-28.md](daily/2026-10-28.md) |
| 2026-10-27 | [daily/2026-10-27.md](daily/2026-10-27.md) |
| 2026-10-26 | [daily/2026-10-26.md](daily/2026-10-26.md) |
