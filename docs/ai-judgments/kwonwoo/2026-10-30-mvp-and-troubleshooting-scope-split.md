---
title: 포트폴리오 트러블슈팅 서사 확보를 위해 좌석 선점 동시성·데드락 방어와 대사 쿼리를 MVP 이후 고도화(M4)로 분리 (ADR 0035)
date: 2026-10-30
updated: 2026-10-30
judge: kwonwoo
ai: Antigravity (Gemini)
verdict: 수용
area: 범위·일정
presentation: 후보
shared: 예
---

# 포트폴리오 트러블슈팅 서사 확보를 위해 좌석 선점 동시성·데드락 방어와 대사 쿼리를 MVP 이후 고도화(M4)로 분리 (ADR 0035)

## 맥락
- 해결하려던 작업: 좌석 선점 담당(`ISE-7` ~ `ISE-18`)의 MVP(M2~M3, ~11/6) 구현 범위를 점검하던 중, 이력서·포트폴리오용 트러블슈팅 사례(문제 재현 → 원인 분석 → 해결 및 전후 수치 비교)를 효과적으로 남기기 위해 동시성 제어와 데드락 방어 항목을 고도화 단계(M4)로 분리할 수 있는지 검토했다.
- AI 활용 방식: 현재 Linear 이슈(`ISE-11` ~ `ISE-14`)에서 팀 전체의 일반 예매 E2E 연동과 MVP 시연에 영향을 주지 않으면서 고도화·트러블슈팅 단계로 뺄 수 있는 항목을 분류하고, 결정 배경·변경 요약 문서와 GitHub 이슈(변경 전 원문)와 비교할 수 있는 변경 후 Linear 이슈 본문 문서를 분리해 작성하도록 요청했다.

## AI 제안
- **MVP 이전(M2~M3) 유지 범위**:
  - `ISE-10`: 5개 테이블 스키마(부분 유니크 인덱스 포함) 및 도메인 엔티티·Repository 구현
  - `ISE-11` (축소): `POST /api/v1/schedules/{scheduleId}/reservations` 기본 입력 검증, 기본 좌석 `AVAILABLE -> HELD` 선점 및 예매 저장, 결제 토큰 포함 `201 Created` 반환
  - `ISE-12` (축소): `Idempotency-Key` 사전 조회 기반 기본 멱등 응답(`201`, `409 IDEMPOTENCY_KEY_REUSED`, `409 RESERVATION_NOT_PAYABLE`) 및 기능 통합 테스트
  - `ISE-13` (축소): 5분 만료 기한 경과 `HELD -> EXPIRED` 기본 만료 스케줄러 구현 (E7·E8 시연 연동)
- **MVP 이후(M4 고도화·트러블슈팅) 분리 범위**:
  - **TS-1 (데드락 방어)**: 다중 좌석 역순 선택 데드락 재현 → 좌석 ID 오름차순 `SELECT ... FOR UPDATE` 잠금 및 `lock_timeout = 500ms` 빠른 실패 적용
  - **TS-2 (매수 제한 우회 방어)**: 동일 사용자 멀티 탭 동시 요청 4매 초과 재현 → `user_schedule_quotas` `ON CONFLICT DO NOTHING` + 조건부 UPDATE 직렬화(D1) 적용
  - **TS-3 (따닥 PK 충돌 복구)**: 동일 `Idempotency-Key` 동시 진입 시 DB PK 충돌 `500` 에러 재현 → 별도 읽기 트랜잭션 재조회로 `201` 복구(`ADR 0032`)
  - **TS-4 (만료 스케줄러 경합 방어)**: `FOR UPDATE SKIP LOCKED` 적용 및 전역 잠금 순서(`quota -> 좌석 오름차순 -> 예매`) 통일
  - **TS-5 (스파이크 대응 아키텍처 전환, `ISE-15`~`ISE-18`)**: 동기형 기준선 대비 Kafka 구역 파티션 접수형(`async`) 전환 및 성능 비교
  - **TS-6 (원장 정합성 검증, `ISE-14`)**: 대규모 동시성·장애 주입 후 `reservation_seats` 원장 기준 `schedule_seats.status`·`user_schedule_quotas.held_count` 대사(Reconciliation) 쿼리 실행

## 판단
- 결과: 수용
- 판단자: kwonwoo
- 근거: 동시성 제어와 데드락 방어 같은 주제는 처음부터 다 넣어두기보다, 기본 구현 이후 고도화 단계에서 문제 재현 및 트러블슈팅과 함께 작성해야 이력서·포트폴리오에 명확한 문제 해결 근거와 전후 비교를 남길 수 있다. 또한 변경 전 이슈 원문은 GitHub 이슈에 남아 있으므로, 결정 배경·변경 요약 문서와 실제 변경될 Linear 이슈 본문 문서를 분리해 두면 전후 차이를 명확히 대조할 수 있다.

## 반영
- [`docs/decisions/0035-defer-seathold-concurrency-and-reconciliation-to-m4.md`](../../decisions/0035-defer-seathold-concurrency-and-reconciliation-to-m4.md): 범위 분리 결정 기록(ADR 0035)
- [`docs/design/reservation/mvp-and-troubleshooting-roadmap.md`](../../design/reservation/mvp-and-troubleshooting-roadmap.md): 결정 배경, 기존 이슈 대비 변경된 점 요약표, 단계별 트러블슈팅 로드맵
- [`docs/design/reservation/linear-issues-after.md`](../../design/reservation/linear-issues-after.md): GitHub 이슈(변경 전)와 대조해 Linear에 반영할 `ISE-7` ~ `ISE-18` 변경 후 본문 전체

## 검증
- 구현 전 (설계 및 마일스톤 이슈 범위 조정)

## 판단 이력
- 2026-10-30 수용: 좌석 선점의 동시성·데드락·스케줄러 경합 방어와 대사 쿼리를 M4 고도화·트러블슈팅 단계로 분리하고, 요약 문서와 변경 후 Linear 이슈 본문 문서를 분리해 작성

## 발표·면접 포인트
- "처음부터 비관적 락 정렬이나 쿼터 직렬화를 모두 넣으면 실제로 어떤 동시성 문제가 얼마나 발생하는지 정량적으로 증명하기 어렵습니다. 그래서 MVP에서는 E2E 연동 가능한 기본 선점 흐름과 DB 부분 유니크 인덱스만 두고, 고도화 단계에서 멀티스레드 테스트로 역순 데드락·멀티 탭 매수 우회·따닥 500 에러를 먼저 재현한 뒤 오름차순 `FOR UPDATE` + `lock_timeout 500ms`, `user_schedule_quotas` 조건부 UPDATE, PK 충돌 재조회 복구를 단계적으로 적용해 해결 전후 수치를 비교했습니다."
