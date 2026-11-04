# 좌석 선점(seathold) MVP 범위 조정 및 고도화·트러블슈팅 분리 요약

좌석 선점 담당(최권우)의 전체 작업(`ISE-7` ~ `ISE-18`)을 **MVP 이전(M2~M3, ~11/6)**과 **MVP 이후(M4 고도화·트러블슈팅, 11/9~11/16)**로 재구분한 결정 배경, 변경된 점 요약, 그리고 6대 트러블슈팅 로드맵을 정리한 문서다.

Linear 이슈에 실제로 붙여넣을 **변경 후 본문(Markdown) 전체**는 별도 문서인 [`linear-issues-after.md`](linear-issues-after.md)에 있다.

- 관련 기록: [`ADR 0035`](../../decisions/0035-defer-seathold-concurrency-and-reconciliation-to-m4.md), [AI 판단 기록](../../ai-judgments/kwonwoo/2026-10-30-mvp-and-troubleshooting-scope-split.md)

---

## 1. 결정을 하게 된 배경 (Why)

1. **포트폴리오·이력서 트러블슈팅 서사(Before → Problem → After) 확보**:
   - 기존 이슈(`ISE-11` ~ `ISE-13`)는 M2~M3 초기 구현 단계부터 좌석 ID 오름차순 비관적 락(`ORDER BY id FOR UPDATE`), `lock_timeout = 500ms`, `user_schedule_quotas` 조건부 UPDATE 직렬화(D1), 따닥 PK 충돌 재조회 복구, 만료 스케줄러 `FOR UPDATE SKIP LOCKED` 및 전역 잠금 순서 통일을 한꺼번에 구현하도록 작성되어 있었다.
   - 그러나 처음부터 모든 동시성·데드락 방어 장치를 넣으면 **"기본 구현에서 어떤 동시성 결함(데드락, 매수 한도 우회, 500 에러, 스케줄러 경합)이 얼마나 발생했고, 이를 어떻게 해결해 지표를 개선했는가"**를 실험 데이터로 증명할 수 없다.
2. **팀 전체 MVP(M2~M3) 연동 무결성 유지**:
   - 예매 흐름(반효승)·결제(안준현)·대기열(권영은)과 연결되는 API 계약([`contracts.md`](contracts.md)), 결제 토큰 포함 `201 Created` 응답, 5분 만료 상태 전이(E7·E8 연동용)는 MVP에서 그대로 제공한다.
   - DB 스키마(`ISE-10`)에 이미 `reservation_seats` 부분 유니크 인덱스(`WHERE status IN ('HELD', 'CONFIRMED')`)가 들어가 있으므로, 애플리케이션 수준의 동시성·락 최적화를 M4로 옮겨도 중복 판매 방어의 최후 방어선은 유지된다.
3. **10/28~10/30 확정된 팀 설계 변경 반영**:
   - 초기 이슈 생성(10/27) 이후 확정된 ① 선점 경로 변경(`POST /api/v1/schedules/{scheduleId}/reservations`) 및 Gateway 입장 토큰 검증 위임(`ISE-20`), ② 선점 응답 필드 10개 확정 및 멱등 재요청 세부 규칙(`ISE-21`, [`ADR 0032`](../../decisions/0032-seat-hold-result-and-idempotency-contract.md)), ③ 복수형 테이블명 통일([`ADR 0030`](../../decisions/0030-table-naming-plural.md)), ④ `seathold`·`reservation` 단방향 모듈 분리([`ADR 0034`](../../decisions/0034-seathold-unidirectional-module-boundary.md))를 이슈 본문에 일괄 반영한다.

---

## 2. 기존 이슈 대비 변경된 점 요약 (`ISE-7` ~ `ISE-18`)

변경 전 원문은 GitHub 이슈(`#10`, `#12`, `#14`~`#22`)에 보관되어 있으며, 각 이슈에서 달라진 핵심 내용은 아래와 같다.

| 구분 | 이슈 번호 (GitHub) | 마일스톤 변화 | 기존 내용 대비 변경된 점 |
|---|---|---|---|
| **MVP 이전**<br>(M2~M3) | **`ISE-7`** (`#10`)<br>동기형 선점 상위 | M2 유지 | • MVP 범위를 **"동기형 기본 일괄 선점 + 기본 멱등성 + E2E 연동"**으로 정의<br>• 동시 100명 경합·동일 사용자 동시 매수 제한 직렬화 완료 기준을 M4 트러블슈팅 이슈로 이관 |
| **MVP 이전** | **`ISE-10`** (`#14`)<br>스키마·엔티티 | M2 유지 (PR #98) | • 단수형 테이블명을 복수형(`user_schedule_quotas`, `reservations`, `reservation_seats`, `seat_hold_requests`, `reservation_status_histories`)으로 변경 (`ADR 0030`)<br>• `seathold`와 `reservation` 단방향 모듈 경계 및 엔티티 소유권 분리 반영 (`ADR 0034`) |
| **MVP 이전** | **`ISE-11`** (`#15`)<br>동기형 선점 API | M2 유지 | • **경로 변경**: `POST /api/v1/reservations` → `POST /api/v1/schedules/{scheduleId}/reservations` (`ISE-20`)<br>• **입장 토큰 검증 제거**: Gateway가 검증하므로 서비스 내부 `X-Entry-Token` 재검증 항목 삭제<br>• **응답 필드 확장**: 8개 → 10개(`scheduleId`, `seatIds` 오름차순 추가, `ISE-21`)<br>• **M4로 분리**: ① `SET LOCAL lock_timeout = '500ms'`, ② 좌석 오름차순 `SELECT ... FOR UPDATE`, ③ `user_schedule_quotas` `ON CONFLICT DO NOTHING` + 조건부 UPDATE 직렬화를 빼고 기본 조회·상태 변경으로 단순화 |
| **MVP 이전** | **`ISE-12`** (`#16`)<br>멱등성·테스트 | M2 유지 | • **멱등 규칙 구체화**: `(user_id, schedule_id, seat_ids)` 일치 판정, 만료/해제된 예매 재요청 시 `409 RESERVATION_NOT_PAYABLE`, 동기형 409 실패 시 `seat_hold_requests` 롤백 명시 (`ADR 0032`)<br>• **M4로 분리**: 동시 따닥 PK 충돌(`DataIntegrityViolationException`) 재조회 복구와 멀티스레드 동시성 테스트(동일 좌석 100요청, 멀티 탭 4매 초과, 역순 좌석 데드락)를 M4 트러블슈팅 이슈로 이관하고 기본 기능·멱등 통합 테스트에 집중 |
| **MVP 이전** | **`ISE-8`** (`#12`)<br>만료·대사 상위 | M3 유지 | • MVP 범위를 **"기본 선점 만료 스케줄러 구현(E7·E8 시연 연동)"**으로 축소<br>• 서브 이슈 중 대사 쿼리(`ISE-14`)와 `SKIP LOCKED`·잠금 순서 통일을 M4로 분리 |
| **MVP 이전** | **`ISE-13`** (`#17`)<br>만료 스케줄러 | M3 유지 | • 복수형 테이블명 반영<br>• **M4로 분리**: `FOR UPDATE SKIP LOCKED` 및 건별 트랜잭션 전역 잠금 순서(`quota → 좌석 오름차순 → 예매`) 강제를 M4 트러블슈팅으로 넘기고, M3에서는 기본 만료 상태 전이(`HELD → EXPIRED`, 좌석 `AVAILABLE` 반환) 구현 |
| **MVP 이후**<br>(M4 고도화) | **`신규 이슈`**<br>동시성·데드락 트러블슈팅 | **M4 신규 생성** | • `ISE-11`·`ISE-12`·`ISE-13`에서 분리한 **4대 동시성 제어 및 트러블슈팅(TS-1 ~ TS-4)** 전담 이슈<br>• 문제 재현 멀티스레드 테스트 작성 → ① 오름차순 `FOR UPDATE` + `lock_timeout 500ms`, ② `user_schedule_quotas` 조건부 UPDATE 직렬화, ③ 따닥 PK 충돌 재조회 복구, ④ 만료 스케줄러 `SKIP LOCKED` + 잠금 순서 통일 적용 → 전후 비교 `docs/troubleshooting.md` 기록 |
| **MVP 이후** | **`ISE-14`** (`#18`)<br>정합성 대사 쿼리 | M3 → **M4 이동** | • 마일스톤을 M3에서 **M4. 고도화**로 이동 (상위 이슈 `ISE-8`에서 분리)<br>• 복수형 테이블명(`schedule_seats`, `reservation_seats`, `user_schedule_quotas`) 반영 및 M4 동시성·장애 주입 후 원장 정합성 검증 단계(TS-6)로 역할 명확화 |
| **MVP 이후** | **`ISE-15`** (`#19`)<br>Kafka 접수형 상위 | M4 유지 | • 선점 경로(`POST /api/v1/schedules/{scheduleId}/reservations`) 및 복수형 테이블명(`seat_hold_requests`, `outbox_events`) 반영 |
| **MVP 이후** | **`ISE-16`** (`#20`)<br>접수형 API·조회 | M4 유지 | • 선점 경로 수정 및 접수형 멱등 재요청 규칙(`contracts.md` 3-2절) 추가<br>• **타인 조회 응답 변경**: 요청자 불일치 시 `403 FORBIDDEN` → **`404 NOT_FOUND`**(존재 여부 은닉, `contracts.md` 2-2절)<br>• **`HELD` 조회 응답 필드 확장**: `reservation` 블록을 4개 필드에서 9개 필드(`reservationId`, `reservationNo`, `scheduleId`, `seatIds`, `status`, `holdExpiresAt`, `totalAmount`, `paymentToken`, `paymentTokenExpiresAt`)로 확장 |
| **MVP 이후** | **`ISE-17`** (`#21`)<br>SeatHoldConsumer | M4 유지 | • 복수형 테이블명(`seat_hold_requests`, `processed_events`) 반영<br>• 컨슈머 내부에서 고도화된 선점 도메인 로직(매수 한도 조건부 UPDATE → 좌석 오름차순 잠금 → 예매 저장)을 호출하도록 명시 |
| **MVP 이후** | **`ISE-18`** (`#22`)<br>접수형 부하 재측정 | M4 유지 | • 부하 재측정 완료 후 `ISE-14` 정합성 대사 쿼리를 실행해 중복 판매 0건 및 좌석·매수 불일치 0건을 최종 검증하는 항목 추가 |

---

## 3. 단계별 수행 범위 (MVP 이전 vs MVP 이후)

### 3-1. MVP 이전에 수행할 것 (M2 ~ M3, ~11/6)
1. **`ISE-10` (M2, PR #98 완료)**: 5개 테이블 Flyway 마이그레이션(`reservation_seats` 부분 유니크 인덱스 포함), `Money` 값 객체, `seathold`·`reservation` 도메인 엔티티 및 Repository
2. **`ISE-11` (M2)**: `POST /api/v1/schedules/{scheduleId}/reservations` 입력 검증, 기본 매수 확인 및 좌석 `AVAILABLE → HELD` 선점, 예매·요청·이력 저장, 결제 토큰 발급기 연동 및 `201 Created` 응답
3. **`ISE-12` (M2)**: `Idempotency-Key` 사전 조회 기반 기본 멱등 재응답(`201`, `409 IDEMPOTENCY_KEY_REUSED`, `409 RESERVATION_NOT_PAYABLE`) 및 기능 통합 테스트 + **M2 동기형 기준선 1회 측정**
4. **`ISE-13` (M3)**: 5분 만료 기한이 지난 `HELD` 예매를 `EXPIRED`로 전이하고 좌석(`AVAILABLE`)과 매수 한도를 반환하는 기본 만료 스케줄러 구현 (E7·E8 시연 연동)

### 3-2. MVP 이후에 수행할 것 (M4 고도화·트러블슈팅, 11/9 ~ 11/16)
1. **고도화 1단계 — 동기형 동시성 제어 및 데드락 트러블슈팅 (`신규 이슈`)**:
   - **TS-1 (다중 좌석 역순 선택 데드락)**: 역순 좌석 동시 선점으로 PostgreSQL Deadlock(`40P01`) 재현 → 좌석 ID 오름차순 `SELECT ... FOR UPDATE` 잠금 + `SET LOCAL lock_timeout = '500ms'` 빠른 실패 적용
   - **TS-2 (멀티 탭 매수 제한 우회)**: 동일 사용자의 동시 요청으로 4매 초과 선점 재현 → `user_schedule_quotas` `ON CONFLICT DO NOTHING` + `held_count + :n <= :maxPerUser` 조건부 UPDATE 직렬화 적용
   - **TS-3 (따닥 연타 PK 충돌 500 에러)**: 동일 `Idempotency-Key` 동시 진입 시 `DataIntegrityViolationException` 500 에러 재현 → 별도 읽기 트랜잭션 재조회로 `201 Created` 복구
   - **TS-4 (만료 스케줄러 경합 및 잠금 역전)**: `FOR UPDATE SKIP LOCKED` 적용 및 건별 트랜잭션 전역 잠금 순서(`quota → 좌석 오름차순 → 예매`) 통일
2. **고도화 2단계 — Kafka 접수형 전환 및 원장 대사 검증 (`ISE-15`~`ISE-18`, `ISE-14`)**:
   - **TS-5 (스파이크 트래픽 대응 Kafka 접수형 전환)**: `ISE-16`(`202 Accepted` Outbox 접수·폴링 조회) + `ISE-17`(`SeatHoldConsumer` 구역 파티션 순차 처리·DLQ·30초 타임아웃) + `ISE-18`(동기형 vs 접수형 직접 발행·Outbox 부하 재측정 및 Zipkin 병목 비교)
   - **TS-6 (원장 정합성 대사 검증)**: `ISE-14` 대사 쿼리로 대규모 부하·장애 주입 후 `reservation_seats` 원장 대비 `schedule_seats.status`·`user_schedule_quotas.held_count` 불일치 0건 증명
