# 0035. 포트폴리오 트러블슈팅 검증을 위해 좌석 선점 동시성·데드락 방어와 대사 쿼리를 M4 고도화 단계로 분리한다

- 상태: 채택
- 날짜: 2026-10-30
- 결정자: 최권우 (좌석 선점 담당)
- 관련 AI 판단: [포트폴리오 트러블슈팅 서사 확보를 위해 좌석 선점 동시성·데드락 방어와 대사 쿼리를 MVP 이후 고도화(M4)로 분리](../ai-judgments/kwonwoo/2026-10-30-mvp-and-troubleshooting-scope-split.md)

## 맥락

초기 Linear 이슈(`ISE-11` ~ `ISE-14`, GitHub `#15` ~ `#18`)는 M2~M3(MVP) 구현 단계에서 다음 항목을 한꺼번에 구현하도록 정의되어 있었다.
1. 다중 좌석 선택 시 역순 잠금 데드락을 막기 위한 좌석 ID 오름차순 `SELECT ... FOR UPDATE` 및 `SET LOCAL lock_timeout = '500ms'`
2. 동일 사용자의 멀티 탭 동시 요청이 회차당 매수 제한(4매)을 우회하지 못하게 직렬화하는 `user_schedule_quotas` `ON CONFLICT DO NOTHING` + 조건부 UPDATE(D1)
3. 수 밀리초 차이의 동시 연타(따닥) 요청이 DB PK 충돌(`DataIntegrityViolationException`)을 만났을 때 `500` 에러 대신 재조회로 복구하는 처리(`ADR 0032`)
4. 선점 만료 스케줄러의 `FOR UPDATE SKIP LOCKED` 및 전역 잠금 순서(`quota → 좌석 오름차순 → 예매`) 통일
5. `reservation_seats` 원장 대비 `schedule_seats.status`·`user_schedule_quotas.held_count` 정합성 대사(Reconciliation) 쿼리(`ISE-14`)

그러나 초기 구현(Baseline)부터 모든 동시성·데드락 방어 장치를 적용하면, 기본 구현에서 실제로 어떤 동시성 결함(PostgreSQL `40P01` 데드락, 매수 한도 초과 선점, 연타 시 500 에러, 스케줄러 락 경합)이 얼마나 발생하는지 재현 로그와 전후 비교 수치를 남길 수 없다. 반면 팀 전체의 M2(일반 예매 E2E 연결)와 M3(미결제 만료 E7·만료 뒤 승인 자동 환불 E8 시연)는 기본 선점 트랜잭션과 기본 만료 스케줄러, 그리고 이미 `ISE-10` 스키마에 반영된 `reservation_seats` 부분 유니크 인덱스만으로도 온전히 동작한다.

## 결정

1. **MVP 이전(M2~M3, ~11/6) 범위 축소 및 집중**:
   - `ISE-11`(M2): 확정된 경로(`POST /api/v1/schedules/{scheduleId}/reservations`)와 응답 계약([`contracts.md`](../design/reservation/contracts.md))에 맞춰 입력 검증, 기본 매수 확인 및 좌석 `AVAILABLE → HELD` 상태 변경, 예매 저장, 결제 토큰 발급기 연동에 집중한다.
   - `ISE-12`(M2): `Idempotency-Key` 사전 조회 기반 기본 멱등 재응답(`201`, `409 IDEMPOTENCY_KEY_REUSED`, `409 RESERVATION_NOT_PAYABLE`)과 기능 통합 테스트를 수행한다.
   - `ISE-13`(M3): 만료 시각이 지난 `HELD` 예매를 `EXPIRED`로 전이하고 좌석과 매수를 반환하는 기본 만료 스케줄러를 구현해 E7·E8 시연을 완성한다.
2. **MVP 이후(M4 고도화·트러블슈팅, 11/9~11/16)로 동시성 제어 및 대사 쿼리 분리**:
   - M4 전반에 **동기형 동시성·데드락 트러블슈팅 이슈**를 두어 멀티스레드 테스트로 ① 역순 좌석 데드락(TS-1), ② 멀티 탭 4매 한도 우회(TS-2), ③ 따닥 연타 PK 충돌 500 에러(TS-3), ④ 만료 스케줄러 경합 및 잠금 역전(TS-4)을 먼저 재현·측정한 뒤 방어 로직을 적용하고 [`docs/troubleshooting.md`](../troubleshooting.md)에 전후 수치를 기록한다.
   - 정합성 대사 쿼리(`ISE-14`)는 M3에서 M4로 이동해 동시성 테스트 및 Kafka 접수형 전환(`ISE-15`~`ISE-18`) 이후 원장 정합성을 검증하는 수단(TS-6)으로 사용한다.

## 검토한 대안

- **초기 계획대로 M2~M3에 모든 동시성·데드락 방어를 즉시 구현**:
  - 처음부터 완성된 코드가 들어가지만, 문제 상황을 인위적으로 되돌려 실험하지 않는 한 Before/After 비교 데이터와 트러블슈팅 근거를 확보하기 어렵고 M2 일정 부담이 커진다.

## 결과

- 얻는 것:
  - M2~M3 기간에 팀 전체 E2E 연동(선점 ↔ 예매 흐름 ↔ 결제)을 빠르게 마칠 수 있다.
  - M4에서 각 동시성·락 경합 문제의 실제 재현 로그와 해결 전후 정량 지표를 확보해 발표 자료와 포트폴리오의 완성도를 높인다.
- 감수하는 것:
  - MVP 시연 시점(M3)에는 애플리케이션 수준의 오름차순 비관적 락과 쿼터 조건부 UPDATE 대신 기본 트랜잭션과 DB 부분 유니크 인덱스로 동작하며, M4 초반에 리팩터링 커밋이 추가된다.
- 후속 작업:
  - [`docs/design/reservation/mvp-and-troubleshooting-roadmap.md`](../design/reservation/mvp-and-troubleshooting-roadmap.md) 및 [`docs/design/reservation/linear-issues-after.md`](../design/reservation/linear-issues-after.md)를 기준으로 Linear 이슈 본문을 갱신한다.
