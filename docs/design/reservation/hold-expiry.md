# 좌석 선점 만료 스케줄러 (ISE-13)

상태: 구현 완료, 논의 대기 (2026-11-02, 좌석 선점 담당 최권우)

결제되지 않은 채 선점 기한이 지난 예매를 주기적으로 회수해 좌석과 보유 매수를 돌려준다(SA 8-3, E7). 예매 흐름 담당(반효승)의 결제 결과 소비자가 아직 시작 전이라, 좌석 선점 쪽이 먼저 구현하고 겹치는 부분은 2절에 논의 항목으로 남겼다. 구현 뒤 합의 결과에 따라 고친다.

## 1. 구현한 것

### 처리 흐름
스케줄러가 5초마다(`fixedDelay`) 기한이 지난 HELD 예매 ID를 최대 100건 조회한다. 그다음 한 건씩 각각의 트랜잭션에서 아래 순서로 처리한다.

1. `reservations`를 HELD → EXPIRED로 바꾼다. 조건부 UPDATE이고 조건은 `status = 'HELD' AND hold_expires_at <= now`다. 0행이면 다른 쪽이 먼저 바꾼 것이므로 여기서 끝낸다.
2. `reservation_seats`를 HELD → RELEASED로 바꾼다. 부분 유니크 인덱스에서 빠지므로 같은 좌석을 다시 선점할 수 있다(D2).
3. `schedule_seats`를 HELD → AVAILABLE로 바꾼다.
4. `user_schedule_quotas.held_count`에서 예매 수량만큼 뺀다. `held_count >= quantity`일 때만 뺀다.
5. `reservation_status_histories`에 HELD → EXPIRED, `reason = HOLD_EXPIRED`를 기록한다.

감사자(`updated_by`)는 NULL(시스템)이다(SA 12-1). 한 건이 실패해도 앞서 처리한 건은 커밋된다. 실패한 건은 HELD로 남아 다음 주기에 다시 대상이 된다.

### 코드 위치
| 역할 | 파일 |
|---|---|
| 만료 서비스 | `reservation-service/.../seathold/application/SeatHoldExpiryService.java` |
| 스케줄러 | `reservation-service/.../seathold/infrastructure/scheduling/SeatHoldExpiryScheduler.java` |
| 예매·예매 좌석·매수 조건부 UPDATE | `reservation.domain.repository`의 `ReservationRepository`·`ReservationSeatRepository`·`UserScheduleQuotaRepository`(기존 선점 담당 코드에 메서드 추가) |
| 회차 좌석 반환 | `seathold.domain.repository.SeatHoldInventoryRepository#releaseHeldSeats` |
| 통합 테스트 | `reservation-service/src/test/.../seathold/application/SeatHoldExpiryTest.java` |

설정은 `seathold.expiry.enabled`(기본 true), `seathold.expiry.interval`(기본 5s), `seathold.expiry.batch-size`(기본 100)이다. 기본값은 코드에 두었고 `config-repo`는 바꾸지 않았다. 테스트 프로필은 스케줄러를 끄고 메서드를 직접 호출한다.

### 검증
- `./gradlew :reservation-service:test --no-daemon`: 전체 통과. 새 테스트 5건은 기한 지난 HELD의 만료와 반환, 만료 뒤 다른 사용자의 재선점, 기한 전 HELD 유지, CONFIRMED·RELEASED 미처리, 같은 예매를 두 번 만료해도 반환은 한 번인지를 확인한다.
- 스케줄러를 켜고(`SEATHOLD_EXPIRY_ENABLED=true`, 주기 500ms) 임시 테스트로 확인했다. 백그라운드 스레드(`scheduling-1`)가 기한 지난 예매를 EXPIRED로 바꿨다.

## 2. 논의할 것

| # | 항목 | 상대 | 현재 구현 | 정해야 할 것 |
|---|---|---|---|---|
| 1 | 좌석·매수 반환 코드의 위치 | 반효승 | 회차 좌석 반환을 `seathold`의 `SeatHoldInventoryRepository`에 두었다 | 결제 거절(RELEASED)과 만료 뒤 승인(EXPIRED)을 처리하는 결제 결과 소비자도 같은 반환이 필요하다(ADR 0027). ADR 0034에 따라 `reservation`은 `seathold`를 import하지 못하므로 지금 위치로는 같이 쓸 수 없다 |
| 2 | 결제 결과 소비자와의 경합 규칙 | 반효승 | 예매 전이를 조건부 UPDATE로 먼저 하고, 0행이면 후속 쓰기를 하지 않는다 | 소비자도 같은 규칙(영향 행이 1일 때만 좌석·매수 반환)을 따르는지 확인한다. 이력 `reason` 값도 맞춘다. 스케줄러는 `HOLD_EXPIRED`를 쓴다. 승인 시각이 기한을 넘었을 때 소비자가 남길 값은 미정이다 |
| 3 | `held_count`가 무엇을 세는지 | 반효승 | 만료 시 예매 수량만큼 뺀다 | 결제 확정(CONFIRMED) 뒤에도 `held_count`를 유지해 1인 매수 한도에 계속 넣는지, 확정 때 빼는지를 정한다. 소비자 구현과 대사(ISE-14) 기준이 여기에 달려 있다 |
| 4 | E8 시연 일정 | 반효승, 안준현 | 만료 전이만 있다 | 만료 뒤 승인 → `RefundRequested` 발행은 결제 결과 소비자(ISE-73, 2026-11-02 기준 Backlog)가 있어야 시연된다. M3(11/4–11/6) 안에 들어오는지 확인한다 |
| 5 | 추첨 예매 만료 | 이강민 | `source`와 상관없이 같은 규칙으로 만료한다. 훅 자리에 `TODO(ISE-88)`를 남겼다 | ISE-88의 `onExpired` 훅 호출과 1회 30분 연장을 이 서비스의 어느 지점에 넣을지 정한다. 2026-11-02 기준 추첨 예매를 만드는 코드가 없어 지금 영향은 없다 |
| 6 | 원장 불일치 처리 | 팀 | 회차 좌석이 HELD가 아니거나 보유 매수가 모자라면 롤백하지 않고 WARN 로그만 남긴다 | 판매 여부의 기준은 `reservation_seats`다(D2). 롤백하면 예매가 HELD로 남아 매 주기 같은 실패를 되풀이한다. 이 처리와 알림 방식을 대사(ISE-14) 설계 때 확정한다 |
| 7 | 설정을 `config-repo`에 둘지 | 팀 | 코드 기본값만 있다 | 운영·부하 테스트 프로필에서 주기와 배치 크기를 바꿀 필요가 생기면 `config-repo/reservation-service.yml`에 추가한다. 공유 설정이라 contract 이슈 대상이다 |

1번 항목은 선택지가 셋이다.

| 안 | 내용 | 장점 | 단점 |
|---|---|---|---|
| A | 회차 좌석 반환 메서드를 `concert` 모듈 저장소로 옮긴다 | 좌석 상태 전이가 좌석을 가진 모듈에 모인다 | `concert`는 반효승 담당이고, 선점(AVAILABLE → HELD)과 반환이 서로 다른 모듈에 나뉜다 |
| B | "예매 회수"(예매 좌석 해제, 회차 좌석 반환, 매수 차감)를 `reservation` 쪽 공용 컴포넌트로 두고, 만료 스케줄러와 결제 결과 소비자가 함께 호출한다 | 반환 규칙이 한 곳에 있어 어긋나지 않는다. ADR 0034의 단방향 의존(`seathold → reservation`)과도 맞는다 | 공용 컴포넌트의 소유자를 정해야 한다 |
| C | 지금처럼 두고 소비자는 별도로 구현한다 | 지금 바꿀 것이 없다 | 같은 반환 규칙이 두 곳에 생겨 어긋날 수 있다 |

좌석 선점 담당 의견은 B다. 결제 결과 소비자를 구현하기 전에 정하면 옮기는 비용이 가장 적다.

## 3. M4로 미룬 것

ADR 0035에 따라 아래는 이번 구현에 넣지 않았다. M4 동시성 트러블슈팅(TS-4)에서 문제를 재현한 뒤 적용한다.
- 만료 대상 조회에 `FOR UPDATE SKIP LOCKED` 적용. 다중 인스턴스에서 같은 대상을 함께 집어 드는 것을 막는다. 지금은 조건부 UPDATE 덕분에 중복 반환은 없고, 잠금 대기만 생긴다.
- 전역 잠금 순서(`user_schedule_quotas` → `schedule_seats` 오름차순 → `reservations`)로 통일. 지금 스케줄러는 예매 행을 먼저 잠근다.
