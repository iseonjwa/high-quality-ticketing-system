# 좌석 선점 Linear 이슈 변경 후 본문 모음 (`ISE-7` ~ `ISE-18`)

변경 전 이슈 원문은 GitHub 이슈(`#10`, `#12`, `#14`~`#22`)에 보관되어 있으므로, 이 문서에는 **Linear에 반영할 변경 후(After) 제목·마일스톤·본문(Markdown)만** 수록한다.
결정을 내린 배경과 기존 대비 달라진 점 요약은 [`mvp-and-troubleshooting-roadmap.md`](mvp-and-troubleshooting-roadmap.md)에서 확인할 수 있다.

---

# PART 1. MVP 이전에 진행할 이슈 (M2 ~ M3, ~11/6)

---

## 1. `ISE-7` (GitHub `#10`, 상위 이슈)
- **마일스톤**: `M2. 일반 예매 한 흐름 연결`
- **제목**: `feat(reservation): 동기형 좌석 일괄 선점 및 기본 멱등성 구현 (MVP)`

```markdown
## 배경

* 관련 문서: `docs/core/SA MASTER.md` 6-4절, 11-5절, 12-3절, `docs/design/reservation/contracts.md`, `docs/design/reservation/mvp-and-troubleshooting-roadmap.md`
* 일반 예매 한 흐름(입장 → 선점 → 결제 → 확정)을 API 호출로 끝까지 연결하기 위해 동기형 좌석 일괄 선점 API와 기본 멱등성 처리를 구현한다.
* 포트폴리오 트러블슈팅 서사(Before/After 비교)를 위해 MVP에서는 기본 선점 트랜잭션과 DB 부분 유니크 인덱스(`reservation_seats`) 방어까지 적용하고, 다중 좌석 데드락 방어(`ORDER BY id FOR UPDATE`, `lock_timeout`), `user_schedule_quotas` 조건부 UPDATE 직렬화, 연타 PK 충돌 복구는 M4 동시성 고도화 이슈에서 문제 재현과 함께 적용한다.

## 범위 (MVP)

* 좌석 선점·예매 관련 5개 테이블 스키마(Flyway) 및 `seathold`·`reservation` 단방향 도메인 엔티티·저장소 구성 (`ISE-10`)
* `POST /api/v1/schedules/{scheduleId}/reservations` 동기형 일괄 선점 기본 로직 및 결제 토큰 포함 `201 Created` 응답 (`ISE-11`)
* `Idempotency-Key`(`seat_hold_requests` PK) 기반 기본 멱등 재요청 응답 및 API 기능 통합 테스트 (`ISE-12`)

## 서브 이슈

- [x] `ISE-10`: `feat(reservation): 좌석 선점 테이블 스키마 및 도메인 엔티티 구현`
- [ ] `ISE-11`: `feat(reservation): 동기형 좌석 일괄 선점 기본 트랜잭션 및 API 구현`
- [ ] `ISE-12`: `feat(reservation): 선점 API 기본 멱등성 처리 및 기능 통합 테스트 작성`

## 완료 기준

- [ ] `POST /api/v1/schedules/{scheduleId}/reservations` 호출 시 좌석이 `HELD`로 변경되고 결제 토큰이 포함된 `201 Created` 응답이 반환된다.
- [ ] 이미 선점된 좌석 요청 시 `409 SEAT_ALREADY_HELD`, 회차당 매수(4매) 초과 시 `409 PURCHASE_LIMIT_EXCEEDED`, 오픈 전 요청 시 `409 SALE_NOT_OPEN`이 반환된다.
- [ ] 같은 `Idempotency-Key` 재요청 시 예매가 중복 생성되지 않고 계약(`contracts.md` 3절)에 따른 응답이 반환된다.
```

---

## 2. `ISE-10` (GitHub `#14`, 서브 이슈 — PR #98 진행 중)
- **마일스톤**: `M2. 일반 예매 한 흐름 연결`
- **제목**: `feat(reservation): 좌석 선점 테이블 스키마 및 도메인 엔티티 구현`

```markdown
## 배경

* 상위 이슈: `ISE-7` 동기형 좌석 일괄 선점 및 기본 멱등성 구현 (MVP)
* 관련 문서: `docs/core/SA MASTER.md` 12-1절, 12-3절, 12-6절 (`ADR 0030` 복수형 테이블명, `ADR 0034` 단방향 모듈 경계 / 설계 결함 D1, D2)
* 좌석 선점과 중복 판매 이중 방어를 위한 DB 제약조건 및 도메인 엔티티를 구성한다.
* 트래픽 집중 지점 격리를 위해 선점 입구 전용 엔티티(`SeatHoldRequest`)는 `seathold.domain`에, 예매 생명주기 전반에서 공유하는 원장 엔티티(`Reservation`, `ReservationSeat`, `ReservationStatusHistory`, `UserScheduleQuota`)는 `reservation.domain`에 두고 `seathold -> reservation` 단방향 의존 규칙을 지킨다 (`ADR 0034`).

## 할 일

- [x] Flyway 마이그레이션 스크립트 작성 (`reservation` DB, PK는 `BIGINT GENERATED ALWAYS AS IDENTITY`, 시간은 `TIMESTAMPTZ`, 금액은 `NUMERIC(15,0)`)
  * `user_schedule_quotas`: `(user_id, schedule_id)` 복합 PK, `held_count SMALLINT NOT NULL DEFAULT 0 CHECK (held_count >= 0)`, `BaseEntity` 감사 컬럼 포함 (D1)
  * `reservations`: `id` PK, `reservation_no VARCHAR(20)` UNIQUE(고객용 번호 분리), `request_id UUID` UNIQUE, `status` CHECK (`HELD`, `CONFIRMED`, `EXPIRED`, `RELEASED`, `CANCELED`), `source` CHECK (`GENERAL`, `LOTTERY`), `INDEX(status, hold_expires_at)`, `INDEX(user_id, created_at)`, `BaseEntity` 감사 컬럼 포함
  * `reservation_seats`: `id` PK, `price NUMERIC(15,0)`, `status` CHECK (`HELD`, `CONFIRMED`, `RELEASED`), 부분 유니크 인덱스 `UNIQUE(schedule_seat_id) WHERE status IN ('HELD', 'CONFIRMED')`, `BaseEntity` 감사 컬럼 포함 (D2, #58)
  * `seat_hold_requests`: `request_id UUID` PK, `seat_ids BIGINT[]`, `status` CHECK (`REQUESTED`, `HELD`, `REJECTED`), `reservation_id` FK(nullable), `reason_code VARCHAR(40)`, `INDEX(status, created_at)`, `BaseEntity` 감사 컬럼 포함 (#33)
  * `reservation_status_histories`: 기록용 테이블로 `BaseEntity` 없이 `created_at`만 포함, `reservation_id` FK 및 INDEX, `from_status`, `to_status`, `reason` (12-1절)
- [x] 도메인 `Money` 값 객체(`BigDecimal`, 소수 자리 0 고정, 음수 금지, `compareTo` 비교) 및 `seathold`·`reservation` 4계층(`domain`, `infrastructure`) 엔티티·Repository 인터페이스 작성

## 완료 기준

- [x] `reservation` DB에 Flyway 마이그레이션이 오류 없이 적용된다.
- [x] 동일 `schedule_seat_id`에 대해 `status = 'HELD'`인 `reservation_seats` 2건 INSERT 시 부분 유니크 제약 위반이 발생하고, 기존 행이 `RELEASED`일 때는 새 `HELD` INSERT가 성공한다 (#58).
```

---

## 3. `ISE-11` (GitHub `#15`, 서브 이슈)
- **마일스톤**: `M2. 일반 예매 한 흐름 연결`
- **제목**: `feat(reservation): 동기형 좌석 일괄 선점 기본 트랜잭션 및 API 구현`

```markdown
## 배경

* 상위 이슈: `ISE-7` 동기형 좌석 일괄 선점 및 기본 멱등성 구현 (MVP)
* 관련 문서: `docs/core/SA MASTER.md` 6-4절, 7-3절, 11-3절, 11-5절, `docs/design/reservation/contracts.md`
* 일반 예매 흐름 연결(M2)을 위해 동기형 좌석 일괄 선점 API와 결제 토큰 발급 연동을 구현한다.
* 입장 토큰(`X-Entry-Token`)의 서명·만료·`sub == X-User-Id`·`scheduleId` 일치 검증은 Gateway가 수행하므로(`ISE-20`), 선점 서비스는 재검증하지 않고 도메인 입력 검증과 선점 상태 전이에 집중한다.
* *(참고)* 다중 좌석 역순 잠금 데드락 방어(`ORDER BY id FOR UPDATE`, `lock_timeout 500ms`)와 멀티 탭 동시 매수 제한 직렬화(`user_schedule_quotas` 조건부 UPDATE)는 M4 동시성 트러블슈팅 이슈에서 문제 재현 후 적용한다.

## 할 일

- [ ] `POST /api/v1/schedules/{scheduleId}/reservations` 요청 입력 및 판매 조건 검증 구현 (`contracts.md` 1절, 4절)
  * 필수 헤더(`X-User-Id`, `Idempotency-Key` UUID 형식) 및 경로 변수 `scheduleId`(양수) 확인 (`400 VALIDATION_FAILED`)
  * `seatIds` 개수(1개 이상 `max_per_user` 이하) 및 요청 배열 내 중복 좌석 ID 검증 (`400 SEAT_COUNT_MISMATCH`)
  * 회차(`schedules`) 존재 여부(`404 NOT_FOUND`) 및 서버 시각 기준 판매 오픈(`now >= open_at`) 확인 (`409 SALE_NOT_OPEN`, E4)
  * 요청한 `seatIds`가 모두 해당 `scheduleId`에 속하는지 확인 (`400 SEAT_COUNT_MISMATCH`)
- [ ] 동기형 일괄 선점 기본 트랜잭션 구현
  1. 사용자의 해당 회차 보유 매수 확인 및 `held_count + 요청 매수 <= max_per_user` 검사 (`409 PURCHASE_LIMIT_EXCEEDED`, E3) 및 `user_schedule_quotas` 반영
  2. 요청 좌석(`schedule_seats`)이 모두 `AVAILABLE`인지 확인하고 `HELD`로 변경 (하나라도 이미 선점/판매되었거나 `reservation_seats` 부분 유니크 제약 위반 시 전체 롤백 후 `409 SEAT_ALREADY_HELD`, E1)
  3. 선점 시점의 `schedule_seats.price`를 `reservation_seats.price`에 복사하고 `Money`로 합산해 `reservations.total_amount` 계산
  4. `reservations(HELD)`, `reservation_seats(HELD)`, `seat_hold_requests(HELD)`, `reservation_status_histories` 저장
- [ ] 결제 토큰 발급기(`PaymentTokenIssuer`, `exp = holdExpiresAt - 10초`) 연동 및 `201 Created` 응답 반환 (`contracts.md` 2-1절)
  * 응답 `data` 필드(10개): `requestId`, `reservationId`, `reservationNo`, `scheduleId`, `seatIds`(오름차순), `status("HELD")`, `holdExpiresAt`, `totalAmount`, `paymentToken`, `paymentTokenExpiresAt`

## 완료 기준

- [ ] 정상 선점 시 `201 Created`와 함께 `contracts.md` 2-1절의 10개 응답 필드가 모두 반환된다.
- [ ] 오픈 시각 이전 요청 시 `409 SALE_NOT_OPEN`, 매수 제한 초과 시 `409 PURCHASE_LIMIT_EXCEEDED`가 반환된다.
- [ ] 이미 선점된 좌석이 1개라도 포함되면 전체 좌석이 롤백되고 `409 SEAT_ALREADY_HELD`가 반환되며 실패한 `seat_hold_requests` 행이 남지 않는다 (`ADR 0032`).
```

---

## 4. `ISE-12` (GitHub `#16`, 서브 이슈)
- **마일스톤**: `M2. 일반 예매 한 흐름 연결`
- **제목**: `feat(reservation): 선점 API 기본 멱등성 처리 및 기능 통합 테스트 작성`

```markdown
## 배경

* 상위 이슈: `ISE-7` 동기형 좌석 일괄 선점 및 기본 멱등성 구현 (MVP)
* 관련 문서: `docs/design/reservation/contracts.md` 3절, `docs/decisions/0032-seat-hold-result-and-idempotency-contract.md`
* 네트워크 타임아웃 등으로 같은 `Idempotency-Key` 재요청이 왔을 때 중복 선점을 막고 기존 예매 결과를 일관되게 반환한다.
* *(참고)* 수 밀리초 차이의 동시 연타(따닥)로 발생하는 DB PK/Unique 충돌(`DataIntegrityViolationException`) 재조회 복구와 멀티스레드 동시성 검증 테스트(100명 동시 경합, 역순 데드락, 멀티 탭 매수 우회)는 M4 동시성 트러블슈팅 이슈에서 다룬다.

## 할 일

- [ ] `Idempotency-Key` 헤더(`seat_hold_requests.request_id`) 기반 멱등 재요청 처리 구현 (`contracts.md` 3절, E2)
  * 선점 진입 시 `seat_hold_requests`에서 `request_id = Idempotency-Key` 조회
  * 기존 행이 있을 때 본문 일치 판정: `user_id == X-User-Id`, `schedule_id == 경로 scheduleId`, `seat_ids == sort(request.seatIds)` 중 하나라도 다르면 `409 IDEMPOTENCY_KEY_REUSED` 반환
  * 모두 일치하고 연결된 예매가 유효한 `HELD`(`now < holdExpiresAt - 10초`)이면 좌석 잠금 재시도 없이 `201 Created`와 결제 토큰 반환
  * 모두 일치하고 이미 결제 확정(`CONFIRMED`)된 경우 `201 Created`(`status: "CONFIRMED"`, `paymentToken: null`) 반환
  * 모두 일치하나 이미 `EXPIRED`·`RELEASED`·`CANCELED`되었거나 `now >= holdExpiresAt - 10초`인 경우 `409 RESERVATION_NOT_PAYABLE` 반환
- [ ] 선점 API 및 멱등성 기능 통합 테스트 작성
  * 정상 일괄 선점(`201 Created`) 및 첫 선점 시 `user_schedule_quotas` 생성 확인 (E16)
  * 동일 `Idempotency-Key` + 동일 본문 재요청 시 `201` 동일 예매 반환, 다른 좌석/다른 회차/다른 사용자 재요청 시 `409 IDEMPOTENCY_KEY_REUSED`, 만료 후 재요청 시 `409 RESERVATION_NOT_PAYABLE` 검증 (E2)
  * 이미 선점된 좌석 요청 시 `409 SEAT_ALREADY_HELD` 및 트랜잭션 롤백 후 재시도 가능 여부 검증

## 완료 기준

- [ ] `contracts.md` 3절의 멱등 재요청 케이스와 기본 예외 시나리오 통합 테스트가 모두 통과한다.
```

---

## 5. `ISE-8` (GitHub `#12`, 상위 이슈)
- **마일스톤**: `M3. MVP 완성·배포`
- **제목**: `feat(reservation): 좌석 선점 만료 스케줄러 구현 (MVP)`

```markdown
## 배경

* 관련 문서: `docs/core/SA MASTER.md` 6-4절, 8-3절, `docs/design/reservation/mvp-and-troubleshooting-roadmap.md` (예외 시나리오 E7, E8)
* 선점 유지 시간(5분) 안에 결제되지 않은 예매를 만료(`EXPIRED`) 처리해 좌석과 매수 한도를 반환함으로써 M3의 실패 경로 시연(E7 미결제 만료, E8 만료 뒤 결제 승인 도착 시 자동 환불)을 완성한다.
* 다중 인스턴스 경합 방어(`FOR UPDATE SKIP LOCKED`), 전역 잠금 순서 통일, 원장 정합성 대사 쿼리(`ISE-14`)는 M4 고도화 단계에서 트러블슈팅과 함께 진행한다.

## 범위 (MVP)

* 만료 기한이 지난 `HELD` 예매 주기적 조회 및 `EXPIRED` 상태 전이, 좌석(`AVAILABLE`)·매수 한도 반환 스케줄러 구현 (`ISE-13`)

## 서브 이슈

- [ ] `ISE-13`: `feat(reservation): 좌석 선점 만료 스케줄러 기본 구현`

## 완료 기준

- [ ] 선점 만료 시각(`hold_expires_at`)이 지난 `HELD` 예매가 자동으로 `EXPIRED` 처리되고 좌석(`AVAILABLE`)과 매수 한도(`held_count`)가 반환되어 다른 사용자가 다시 선점할 수 있다 (E7).
```

---

## 6. `ISE-13` (GitHub `#17`, 서브 이슈)
- **마일스톤**: `M3. MVP 완성·배포`
- **제목**: `feat(reservation): 좌석 선점 만료 스케줄러 기본 구현`

```markdown
## 배경

* 상위 이슈: `ISE-8` 좌석 선점 만료 스케줄러 구현 (MVP)
* 관련 문서: `docs/core/SA MASTER.md` 8-3절, 12-1절 (예외 시나리오 E7, E8)
* 미결제 선점 예매를 주기적으로 회수해 좌석과 매수 한도를 돌려준다.
* *(참고)* 다중 인스턴스 환경의 `FOR UPDATE SKIP LOCKED` 적용과 실시간 결제 확정(`PaymentApproved`) 트랜잭션과의 잠금 순서 역전 데드락 방어는 M4 동시성 트러블슈팅 이슈에서 다룬다.

## 할 일

- [ ] 만료 대상 조회 및 상태 전이 스케줄러 구현
  * `INDEX(status, hold_expires_at)`를 활용해 `status = 'HELD' AND hold_expires_at <= now()`인 만료 대상 예매 조회
  * `reservations`를 `HELD -> EXPIRED`로, `reservation_seats`를 `HELD -> RELEASED`로, `schedule_seats`를 `HELD -> AVAILABLE`로 상태 전이
  * `user_schedule_quotas.held_count`를 예매 수량(`quantity`)만큼 차감
  * `reservation_status_histories`에 `from_status = 'HELD'`, `to_status = 'EXPIRED'`, `reason = 'HOLD_EXPIRED'`, 감사자 `NULL`(시스템) 기록 (12-1절)
- [ ] 만료 처리 통합 테스트 작성
  * 만료 시각이 지난 `HELD` 예매가 스케줄러 실행 후 `EXPIRED`로 바뀌고 좌석이 `AVAILABLE`로 돌아오는지 검증 (E7)
  * 이미 `CONFIRMED` 또는 `RELEASED`로 바뀐 예매는 만료 처리되지 않는지 검증

## 완료 기준

- [ ] 만료 기한이 지난 `HELD` 예매가 스케줄러 실행 후 `EXPIRED`로 바뀌고, 다른 사용자가 해당 좌석을 다시 선점할 수 있다 (E7).
- [ ] M3 시연에서 만료 후 결제 승인 도착 시 자동 환불(E8) 흐름과 정상 연동된다.
```

---
---

# PART 2. MVP 이후에 진행할 고도화·트러블슈팅 이슈 (M4, 11/9 ~ 11/16)

---

## 7. `[신규 생성 이슈]` 동기형 동시성·데드락 트러블슈팅 이슈
- **마일스톤**: `M4. 고도화`
- **제목**: `refactor(reservation): 좌석 선점·만료 동시성 제어 및 데드락 방어 고도화 (트러블슈팅)`

```markdown
## 배경

* 관련 문서: `docs/core/SA MASTER.md` 8-4절, 12-3절, 12-7절, `docs/design/reservation/mvp-and-troubleshooting-roadmap.md` (설계 결함 D1·D2, 예외 시나리오 E1·E2·E3, `ADR 0032`)
* MVP 기본 구현 위에 멀티스레드 동시성 부하를 주입해 **① 다중 좌석 역순 잠금 데드락, ② 멀티 탭 동시 요청에 의한 1인 4매 제한 우회, ③ 버튼 연타(따닥) 동시 진입 시 PK 충돌 500 에러, ④ 만료 스케줄러와 실시간 선점·결제 간 잠금 역전 및 중복 실행** 문제를 실제로 재현하고 해결한 뒤 전후 비교 수치를 `docs/troubleshooting.md`에 기록한다.

## 할 일

- [ ] **[TS-1] 다중 좌석 역순 잠금 데드락 재현 및 오름차순 비관적 락 + `lock_timeout` 적용**
  * 역순 좌석(예: `[1024, 1023]` vs `[1023, 1024]`) 동시 요청 테스트로 PostgreSQL Deadlock 재현
  * 좌석 ID 오름차순 `SELECT ... FOR UPDATE` 잠금 강제 및 `SET LOCAL lock_timeout = '500ms'` 적용 (`lock_timeout` 초과 시 `409 SEAT_ALREADY_HELD` 빠른 실패)
- [ ] **[TS-2] 동일 사용자 멀티 탭 매수 제한(4매) 우회 재현 및 `user_schedule_quotas` 조건부 UPDATE 직렬화 (D1)**
  * 동일 사용자의 동시 요청으로 4매 초과 선점 발생 재현
  * `INSERT INTO user_schedule_quotas ... ON CONFLICT (user_id, schedule_id) DO NOTHING` 보장 후 `WHERE held_count + :n <= :maxPerUser` 원자적 조건부 UPDATE 적용 (0행 갱신 시 `409 PURCHASE_LIMIT_EXCEEDED`)
- [ ] **[TS-3] 선택 완료 연타(따닥) 동시 진입 시 PK 충돌 `500` 에러 재현 및 재조회 복구 (`ADR 0032`)**
  * 동일 `Idempotency-Key` 동시 진입 시 `DataIntegrityViolationException`으로 인한 500 에러 재현
  * 예외 포착 후 별도 읽기 트랜잭션에서 커밋된 기존 행을 재조회해 `201 Created`(또는 본문 불일치 시 `409 IDEMPOTENCY_KEY_REUSED`)로 복구
- [ ] **[TS-4] 만료 스케줄러 `FOR UPDATE SKIP LOCKED` 및 전역 잠금 순서 통일 (8-4절)**
  * 만료 대상 ID 조회에 `FOR UPDATE SKIP LOCKED` 적용
  * 건별 트랜잭션 분리 및 전역 잠금 순서(`user_schedule_quotas` → `schedule_seats` 오름차순 → `reservations`) 강제
- [ ] 멀티스레드 동시성 통합 테스트 4종 완성 및 `docs/troubleshooting.md` 기록 작성

## 완료 기준

- [ ] 동일 좌석 동시 100명 요청 시 1명만 `201`, 99명은 `409 SEAT_ALREADY_HELD`, 중복 판매 0건 확인 (E1).
- [ ] 동일 사용자 동시 요청 시 합계 4매 초과 선점 0건 확인 (D1, E3).
- [ ] 역순 좌석 동시 요청 시 데드락 0건, 동일 멱등키 동시 연타 시 500 에러 0건 확인 (E2).
- [ ] 문제 재현 로그와 해결 전후 비교 수치가 `docs/troubleshooting.md`에 기록된다.
```

---

## 8. `ISE-14` (GitHub `#18`, 서브 이슈 -> 독립/M4 이슈)
- **마일스톤**: `M4. 고도화` *(기존 M3에서 변경)*
- **제목**: `feat(reservation): 좌석 상태 및 보유 매수 정합성 대사(Reconciliation) 쿼리 구현`

```markdown
## 배경

* 관련 문서: `docs/core/SA MASTER.md` 3장, 6-4절, 12-7절 (설계 결함 D2)
* 판매 여부의 기준(Source of Truth)은 `reservation_seats` 부분 유니크 인덱스(`WHERE status IN ('HELD', 'CONFIRMED')`)다.
* 대규모 동시성 부하 및 장애 주입 테스트 후 조회용 상태인 `schedule_seats.status`나 `user_schedule_quotas.held_count`가 원장(`reservation_seats`)과 어긋나지 않았는지 검증하고 보정하는 대사(Reconciliation) 쿼리를 구현한다.

## 할 일

- [ ] 좌석 상태 불일치 조회 및 보정 쿼리 작성 (`seathold` 대사 서비스)
  * 활성 `reservation_seats`(`HELD`, `CONFIRMED`) 상태와 `schedule_seats.status`(`AVAILABLE`, `HELD`, `SOLD`)가 어긋난 좌석 검출 및 `reservation_seats` 기준 자동 보정
- [ ] 사용자 회차별 보유 매수 불일치 조회 및 보정 쿼리 작성
  * `user_schedule_quotas.held_count`와 해당 사용자·회차의 활성 `reservation_seats`(`HELD`, `CONFIRMED`) 수가 어긋난 행 검출 및 보정
- [ ] 불일치 데이터를 인위적으로 주입한 뒤 대사 쿼리가 정상 검출·보정하는지 검증 테스트 작성 및 부하 테스트 후 정합성 검증에 활용

## 완료 기준

- [ ] 인위적 불일치 주입 후 대사 실행 시 불일치 건수가 0건으로 수렴한다.
- [ ] M4 동시성·부하 테스트 실행 후 대사 쿼리로 좌석·매수 정합성 불일치 0건을 확인한다.
```

---

## 9. `ISE-15` (GitHub `#19`, 상위 이슈)
- **마일스톤**: `M4. 고도화`
- **제목**: `feat(reservation): Kafka 기반 접수형(async) 좌석 선점 및 동기형 대비 재측정`

```markdown
## 배경

* 관련 문서: `docs/core/SA MASTER.md` 3-1절, 6-4절, 10-1절, 10-5절, 11-5절, 12-3절 (`docs/sa-review/03-seat-hold-kafka-design.md`, `docs/design/reservation/contracts.md`, 설계 결함 D4, 예외 시나리오 E15)
* 오픈 정각 스파이크 트래픽을 DB가 감당할 속도로 줄 세우고, 회차·구역(`{scheduleId}:{section}`) 파티션에서 순서대로 처리해 DB 락 경합을 줄인 뒤 동기형 기준선과 비교 측정한다.

## 범위

* `seat-hold.mode`(`sync` / `async`) 설정 분기 및 접수형 `202 Accepted` 접수(`seat_hold_requests` + `outbox_events` 단일 트랜잭션, `ISE-16`)
* `GET /api/v1/reservations/requests/{requestId}` 접수 결과 조회 API (`ISE-16`)
* `SeatHoldConsumer` 파티션 순차 소비 및 선점 처리, DLQ(`PROCESSING_FAILED`)·30초 타임아웃(`TIMEOUT`) 처리 (`ISE-17`)
* 동기형 대비 접수형(직접 발행·Outbox) 부하 재측정 및 Zipkin 병목 분석 (`ISE-18`)

## 서브 이슈

- [ ] `ISE-16`: `feat(reservation): 접수형 좌석 선점 요청 접수(Outbox) 및 결과 조회 API 구현`
- [ ] `ISE-17`: `feat(reservation): SeatHoldConsumer 순차 선점 처리 및 DLQ·타임아웃 대응 구현`
- [ ] `ISE-18`: `test(reservation): 동기형 대비 접수형 좌석 선점 부하 재측정 및 병목 분석`

## 완료 기준

- [ ] `seat-hold.mode=async`에서 `POST /api/v1/schedules/{scheduleId}/reservations` 호출 시 `202 Accepted` 반환 후 비동기 선점이 완료되고, 조회 API에서 `HELD`와 예약·결제 토큰 블록이 반환된다.
- [ ] 소비 실패(DLQ) 및 30초 경과 요청이 각각 `REJECTED(PROCESSING_FAILED)`, `REJECTED(TIMEOUT)`으로 마감된다 (E15).
- [ ] 동일 측정 환경에서 동기형과 접수형의 처리량·평균/p95 응답 시간·컨슈머 랙 비교 결과와 측정 환경 기록표가 문서로 남는다.
```

---

## 10. `ISE-16` (GitHub `#20`, 서브 이슈)
- **마일스톤**: `M4. 고도화`
- **제목**: `feat(reservation): 접수형 좌석 선점 요청 접수(Outbox) 및 결과 조회 API 구현`

```markdown
## 배경

* 상위 이슈: `ISE-15` Kafka 기반 접수형(async) 좌석 선점 및 동기형 대비 재측정
* 관련 문서: `docs/core/SA MASTER.md` 10-1~10-3절, 11-5절, 12-3절, `docs/design/reservation/contracts.md` 2-2절·3-2절 (결정 기록 #19, #33, #46 / 설계 결함 D4)
* 접수형 모드(`seat-hold.mode=async`)에서 좌석 행을 잠그지 않고 접수 요청 행(`seat_hold_requests`)과 Outbox 이벤트(`outbox_events`)를 원자적으로 저장해 빠르게 `202 Accepted`를 반환하고, 폴링용 결과 조회 API를 제공한다.

## 할 일

- [ ] `seat-hold.mode`(`sync` / `async`) 설정 프로퍼티 분기 구성 (`@RefreshScope` 연동 고려, 9-6절)
- [ ] 접수형 `POST /api/v1/schedules/{scheduleId}/reservations` 구현 (`contracts.md` 2-2절, 3-2절)
  * 기본 입력 검증(1~4매, 중복 좌석, 회차 오픈 시각) 및 요청 좌석의 구역(`section`) 확인 후 메시지 키 `{scheduleId}:{section}` 생성
  * 단일 트랜잭션으로 `seat_hold_requests(REQUESTED)` INSERT + `outbox_events(SeatHoldRequested)` INSERT (토픽 `seat-hold.request`, 페이로드: `requestId, userId, scheduleId, section, seatIds, requestedAt`, 10-3절)
  * 동일 `Idempotency-Key` 재요청 시 본문(`userId`, `scheduleId`, `seatIds`)이 같으면 `202 Accepted` + `Location` 반환, 다르면 `409 IDEMPOTENCY_KEY_REUSED` 반환 (`contracts.md` 3-2절)
  * `202 Accepted`, `Location: /api/v1/reservations/requests/{requestId}` 헤더, `{ requestId, status: "REQUESTED", nextPollAfterSeconds: 1 }` 반환
- [ ] `GET /api/v1/reservations/requests/{requestId}` 접수 결과 조회 API 구현 (`contracts.md` 2-2절)
  * 대상 요청이 없거나 요청자(`X-User-Id`)가 `seat_hold_requests.user_id`와 다르면 타인 존재 여부를 숨기기 위해 `404 NOT_FOUND` 반환
  * `REQUESTED`: `{ requestId, status: "REQUESTED", nextPollAfterSeconds: 1 }`
  * `HELD`: `{ requestId, status: "HELD", reservation: { reservationId, reservationNo, scheduleId, seatIds, status, holdExpiresAt, totalAmount, paymentToken, paymentTokenExpiresAt } }`
  * `REJECTED`: `{ requestId, status: "REJECTED", reasonCode }` (`SEAT_ALREADY_HELD`, `PURCHASE_LIMIT_EXCEEDED`, `PROCESSING_FAILED`, `TIMEOUT`)

## 완료 기준

- [ ] `seat-hold.mode=async`에서 선점 요청 시 좌석 락 없이 `202 Accepted`와 `Location` 헤더가 반환되고 `seat_hold_requests`·`outbox_events` 행이 생성된다.
- [ ] 상태별(`REQUESTED`, `HELD`, `REJECTED`) 조회 응답 형식과 타인 조회 차단(`404 NOT_FOUND`)이 `contracts.md` 2-2절 명세와 일치한다.
```

---

## 11. `ISE-17` (GitHub `#21`, 서브 이슈)
- **마일스톤**: `M4. 고도화`
- **제목**: `feat(reservation): SeatHoldConsumer 순차 선점 처리 및 DLQ·타임아웃 대응 구현`

```markdown
## 배경

* 상위 이슈: `ISE-15` Kafka 기반 접수형(async) 좌석 선점 및 동기형 대비 재측정
* 관련 문서: `docs/core/SA MASTER.md` 8-3절, 9-4절, 10-4절, 10-5절, 12-3절 (결정 기록 #46 / 예외 시나리오 E15)
* `seat-hold.request` 토픽을 구독해 구역별 순서대로 선점을 수행하고, 처리 실패로 DLQ에 도달하거나 30초가 지난 요청이 무한 대기(`REQUESTED`)로 남지 않게 닫는다.

## 할 일

- [ ] `SeatHoldConsumer` 구현 (토픽 `seat-hold.request`, 컨슈머 그룹 `reservation.seat-hold`)
  * `processed_events(event_id, 'reservation.seat-hold')` 중복 소비 검사 및 업무 변경과 단일 트랜잭션 기록 (10-4절)
  * `seat_hold_requests`가 `REQUESTED` 상태일 때만 선점 로직(매수 한도 조건부 UPDATE -> 좌석 오름차순 잠금 -> 예매 저장, 선점 만료 시각은 소비·확정 시점 기준 `now() + hold_minutes`) 실행 후 `WHERE status = 'REQUESTED'` 조건부로 `HELD` 및 `reservation_id` 갱신
  * 이미 선택된 좌석이거나 매수 초과 시 좌석·매수 변경은 롤백하고 `seat_hold_requests`를 `REJECTED(SEAT_ALREADY_HELD | PURCHASE_LIMIT_EXCEEDED)`로 조건부 갱신 + `processed_events` 기록
- [ ] DLQ 처리기 및 요청 타임아웃 마감 스케줄러 구현 (8-3절, 10-5절)
  * 소비 재시도 3회(1·2·4초 지수 백오프) 후 `seat-hold.request.dlq` 도달 시 DLQ 처리기가 `seat_hold_requests`를 `REJECTED(PROCESSING_FAILED)`로 조건부 갱신 (E15)
  * `INDEX(status, created_at)`을 사용해 생성 후 30초가 지난 `REQUESTED` 행을 주기적으로 `REJECTED(TIMEOUT)`으로 조건부 갱신하고, 뒤늦게 도착한 메시지는 0행 갱신으로 무시

## 완료 기준

- [ ] 컨슈머가 이벤트를 소비해 정상 선점 시 `HELD`, 좌석 경합 실패 시 `REJECTED(SEAT_ALREADY_HELD)`로 전이된다.
- [ ] 소비 예외 주입 시 3회 재시도 후 DLQ를 거쳐 `REJECTED(PROCESSING_FAILED)`로 조회된다 (E15).
- [ ] `TIMEOUT`으로 닫힌 요청의 지연 메시지가 도착해도 좌석을 선점하지 않고 건너뛴다.
```

---

## 12. `ISE-18` (GitHub `#22`, 서브 이슈)
- **마일스톤**: `M4. 고도화`
- **제목**: `test(reservation): 동기형 대비 접수형 좌석 선점 부하 재측정 및 병목 분석`

```markdown
## 배경

* 상위 이슈: `ISE-15` Kafka 기반 접수형(async) 좌석 선점 및 동기형 대비 재측정
* 관련 문서: `docs/core/SA MASTER.md` 3장, 3-1절, 6-4절, 10-1절, `docs/design/reservation/mvp-and-troubleshooting-roadmap.md` (결정 기록 #47, AI 검증 #8)
* 동일 측정 환경에서 동기형과 접수형의 성능 차이와 포화점을 비교하고, Outbox 폴링 지연 영향 및 DB 락 대기 구간을 Grafana·Zipkin으로 분석해 발표 및 포트폴리오 트러블슈팅 자료로 정리한다.

## 할 일

- [ ] 부하 테스트 담당(권영은)과 함께 동일 시나리오(동시 100명·1초 내 시작·각 10회 및 인기 회차·구역 쏠림 Synthetic Data) 재측정 수행
- [ ] 10-1절 기준 비교 측정 및 지표 수집
  * 비교 대상: 동기형(`sync`), 접수형 직접 발행, 접수형 Outbox 세 경우를 같은 환경에서 비교 (10-1절)
  * 수집 지표: 평균 응답 시간, p95 응답 시간, 처리량(TPS), 포화점, 5xx·타임아웃 오류율(409는 정상 거절로 분리 집계)
  * 접수형은 `202` 접수 응답 시간과 `접수 -> 처리 완료 시간`(Outbox 폴링 500ms + 컨슈머 랙)을 분리 기록
- [ ] Zipkin 트레이스로 구간별(Gateway -> Reservation -> DB -> Kafka) 지연 분석 및 측정 환경 기록표(CPU·메모리·JVM `-Xmx`·커넥션 풀·시나리오·커밋 해시) 작성
- [ ] 재측정 후 `ISE-14` 정합성 대사 쿼리를 실행해 중복 판매 0건 및 좌석·매수 불일치 0건 검증

## 완료 기준

- [ ] 동기형·접수형(직접 발행·Outbox) 비교 측정 결과표와 측정 환경 기록표가 문서로 정리된다.
- [ ] 병목 구간 분석 및 아키텍처 전환 트러블슈팅 내용이 `docs/troubleshooting.md`에 반영된다.
```
