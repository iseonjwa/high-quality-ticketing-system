---
title: 11/2 추첨 MVP와 Gateway 입장 토큰 작업을 이슈·PR 단위로 다시 읽기(코드 리뷰와 개념 정리)
date: 2026-11-02
updated: 2026-11-02
asker: GGangMY
level: 중급
tags: [lottery, transaction, locking, idempotency, postgresql, spring, gateway, jwt, testing, git]
---

# 11/2 추첨 MVP와 Gateway 입장 토큰 작업을 이슈·PR 단위로 다시 읽기

## 질문
오늘 Claude Code와 함께 진행한 이슈와 PR을 단위별로 정리해 달라. 어떤 이슈·PR에 엮이는지, 코드가 어떻게 구성됐는지, 알아야 할 개념을 상세히 설명해서 어떤 질문에도 답할 수 있게 하고 싶다.

## 읽는 법
- 0장에서 추첨 전체 흐름과 패키지 구조를 먼저 본다. 1~9장은 이슈·PR 하나씩 "무엇을 → 어떻게(코드) → 왜(판단) → 테스트가 증명하는 것 → 리뷰 의견 → 예상 질문" 순서다.
- 10장은 여러 이슈에 걸쳐 나온 개념(조건부 UPDATE, 행 잠금, `SKIP LOCKED`, `ON CONFLICT`, 트랜잭션 전파, 멱등성, 포트·어댑터, 변이 테스트, squash 머지와 쌓은 PR)을 한곳에 모은 것이다. 처음 보는 개념은 10장을 먼저 읽어도 된다.
- 코드 경로는 `reservation-service/src/main/java/com/iseonjwa/reservation/` 아래를 `…/`로 줄여 쓴다. 발췌는 이해에 필요한 부분만 옮겼다. 전체는 IntelliJ에서 같은 경로로 열면 된다.

---

## 0. 전체 그림

### 0-1. 추첨이 흘러가는 순서와 담당 이슈

```
[운영자] 추첨 설정 ────────────────── ISE-85 (#179) S석 N개를 LOTTERY 등급으로 떼어 둠
   │                                        └ 좌석 등급 LOTTERY 자체는 ISE-83 (#146)
[사용자] 응모·취소·결과 조회 ─────── ISE-86 (#195) 1인 1회 응모, 마감 전 취소
   │  응모 마감 시각(entry_end_at)
마감 스케줄러 OPEN → CLOSED ──────── ISE-87 (#207)
추첨 스케줄러 CLOSED → DRAWN ─────── ISE-87 순위 계산(ISE-84 도메인) → WON / WAITLISTED / LOST
   │
배정 워커(1초) WON → 예매 HELD ───── ISE-90 (#210) ← 시스템 선점 ISE-88 (#209)
   │  (자동결제 요청 발행: ISE-92, 아직 없음)
결제 결과 처리기 ────────────────── ISE-90 PAID / FAILED / NOT_SENT면 1회 재시도
   │                                        └ 처리기를 부르는 소비자·만료 연결: ISE-143 (M3)
승계 워커(1초) WAITLISTED → WON ─── ISE-93 (#211) 빈 자리만큼 예비자 승격 → 다시 배정 워커
승계 마감(10초) WAITLISTED → LOST ─ ISE-93 추첨 후 30분
종결(1분) DRAWN → DONE, 추첨석 반환 ISE-91 (#224)
```

일반 예매 쪽에서 오늘 함께 한 것: Gateway 입장 토큰 필터 ISE-58 (#208). 좌석 조회·선점 앞에서 대기열을 거쳤는지 확인한다.

### 0-2. 응모(lottery_entries) 상태 전이

```
ENTERED ──취소(마감 전)──▶ CANCELED
   │ 추첨
   ├──▶ WON ──결제 확정──▶ PAID
   │     ├──결제 거절·만료·배정 거절──▶ FAILED (fail_reason)
   │     └──NOT_SENT 1회 재시도: reservation_id만 비움(WON 유지)
   ├──▶ WAITLISTED ──승계──▶ WON
   │        └──승계 마감──▶ LOST
   └──▶ LOST
```

추첨(lotteries) 상태: `OPEN → CLOSED → DRAWN → DONE`. 모든 전이는 "지금 상태가 X일 때만 Y로"라는 조건부 UPDATE로 한다(10-1).

### 0-3. 패키지 구조와 의존 방향

```
…/lottery
  ├ domain          Lottery, LotteryEntry(엔티티), LotteryStatus·LotteryEntryStatus,
  │                 LotteryRepository·LotteryEntryRepository(인터페이스),
  │                 DrawRanker·SeatAllocator·SuccessionPlanner·DrawTimeWindow(순수 계산),
  │                 LotteryErrorCode
  ├ application     LotteryAdminService(설정·수정), LotteryEntryService(응모),
  │                 LotteryDrawService(마감·추첨), LotteryAssignmentService(배정),
  │                 LotteryPaymentResultService(결제 결과), LotterySuccessionService(승계),
  │                 포트: LotterySeatPort, SystemHoldPort, LotteryPaymentResultHandler
  ├ infrastructure  persistence(Spring Data + JPA 구현), seat(포트 어댑터), scheduler(스케줄러)
  └ presentation    AdminLotteryController, LotteryController, dto
```

의존 방향 규칙(설계 `details.md` 4절, ADR 0034):
- lottery → concert(좌석 SQL), lottery → seathold(시스템 선점)은 **포트(인터페이스)와 어댑터**로만 부른다. 서비스를 나누면 어댑터만 HTTP 클라이언트로 바꾼다.
- `reservation`·`concert` 패키지는 `seathold`를 import하지 않는다(ADR 0034). 그래서 시스템 선점 어댑터는 lottery 쪽에 있다.
- lottery 쿼리는 lottery 테이블만 본다. 좌석 테이블은 concert 저장소 메서드를 통해서만 읽고 쓴다.

---

## 1. ISE-83 · PR #146 — 좌석 등급 `LOTTERY`와 일반 선점 제외 (오늘 머지 확인)

오늘은 머지 확인과 브랜치 정리만 했다. 이후 모든 추첨 코드의 전제라 짧게 정리한다.

- **무엇**: `SeatGrade`에 `LOTTERY`를 추가했다. 추첨석은 원래 등급(S 등)에서 떼어 `LOTTERY`로 표시한다(D9, ADR 0037).
- **핵심 코드**: 일반 선점의 조건부 UPDATE에 `AND s.grade <> :lottery`가 있다(`…/seathold/infrastructure/persistence/JpaSeatHoldInventoryRepository.holdAvailableSeats`). 일반 사용자가 추첨석 ID를 보내도 갱신 행 수가 모자라 409 `SEAT_ALREADY_HELD`가 된다.
- **왜 등급으로 떼나**: 추첨과 일반 판매가 같은 좌석을 두고 다투지 않게 한다. 별도 플래그 컬럼보다 기존 등급 체계와 가격표를 그대로 쓰고, 종결 때 원래 등급(`source_grade`)으로 되돌리면 된다.

---

## 2. ISE-85 · PR #179 — 추첨 테이블과 설정·수정 API

### 2-1. 무엇을 만들었나
- 마이그레이션 `V20261102_1__lottery_tables.sql`: `lotteries`, `lottery_entries`
- `POST /api/v1/admin/schedules/{scheduleId}/lotteries`(설정), `PATCH /api/v1/admin/lotteries/{lotteryId}`(수정)

### 2-2. 테이블과 제약 — 규칙을 DB가 지킨다
```sql
CREATE TABLE lotteries (
    ...
    seed BYTEA NOT NULL,                                     -- 32바이트 시드(ADR 0041)
    CONSTRAINT uq_lotteries_schedule_id UNIQUE (schedule_id),  -- 회차당 추첨 1개(D2)
    CONSTRAINT chk_lotteries_source_grade CHECK (source_grade <> 'LOTTERY'),
    CONSTRAINT chk_lotteries_max_per_entry CHECK (max_per_entry BETWEEN 1 AND 2),
    CONSTRAINT chk_lotteries_period CHECK (entry_start_at < entry_end_at AND entry_end_at <= draw_at),
    CONSTRAINT chk_lotteries_seed CHECK (octet_length(seed) = 32),
    CONSTRAINT chk_lotteries_status CHECK (status IN ('OPEN', 'CLOSED', 'DRAWN', 'DONE'))
);
CREATE TABLE lottery_entries (
    ...
    CONSTRAINT uq_lottery_entries_request_id UNIQUE (request_id),        -- 응모 멱등
    CONSTRAINT uq_lottery_entries_lottery_user UNIQUE (lottery_id, user_id), -- 1인 1응모, 취소해도 남김
    ...
);
CREATE INDEX idx_lottery_entries_lottery_status_draw_rank ON lottery_entries (lottery_id, status, draw_rank); -- 승계
CREATE INDEX idx_lottery_entries_won_unassigned ON lottery_entries (lottery_id)
    WHERE status = 'WON' AND reservation_id IS NULL;                     -- 배정 워커(부분 인덱스)
```
- **왜 애플리케이션 검증과 DB 제약을 둘 다 두나**: 애플리케이션 검증은 친절한 에러(400·409)를 주고, DB 제약은 버그나 동시성으로 검증이 뚫려도 잘못된 데이터가 저장되지 않게 하는 마지막 방어선이다.
- **부분 인덱스(partial index)**: `WHERE` 조건을 만족하는 행만 인덱스에 넣는다. 배정 워커는 "WON이고 예매가 없는" 소수의 행만 찾으므로 인덱스가 작고 빠르다.
- **seed가 BYTEA인 이유(ADR 0041)**: SA에는 `BIGINT`로 적혀 있었지만, 이미 머지된 순위 계산 `DrawRanker`(ISE-84)가 32바이트 시드를 쓴다. 8바이트로 줄이면 추측 공격에 약해지고 계산 코드를 다시 고쳐야 해서, 스키마를 코드에 맞추고 SA를 고쳤다. 설계와 다르게 갔으므로 ADR로 남겼다.

### 2-3. 설정 흐름 코드(`LotteryAdminService.create`)
```java
@Transactional
public LotteryResult create(CreateLotteryCommand command) {
    Schedule schedule = scheduleRepository.findByIdForUpdate(command.scheduleId())   // ① 회차 행 잠금
            .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
    if (lotteryRepository.existsByScheduleId(command.scheduleId())) {               // ② 중복 확인
        throw new BusinessException(LotteryErrorCode.LOTTERY_ALREADY_EXISTS);
    }
    Lottery lottery = Lottery.create(command.scheduleId(), command.settings(),       // ③ 규칙 검사
            scheduleTimes(schedule), Instant.now(clock));
    var settings = command.settings();
    convert(command.scheduleId(), settings, settings.seatCount());                   // ④ 좌석 전환
    return LotteryResult.of(lotteryRepository.save(lottery));                        // ⑤ 저장
}
```
- ① `SELECT ... FOR UPDATE`로 회차 행을 잠근다. 같은 회차의 추첨 설정·수정·좌석 생성이 한 줄로 선다. 그래서 ②의 "있나?" 확인과 ⑤ INSERT 사이에 다른 요청이 끼지 못한다(check-then-act 경합 방지). UNIQUE 제약은 마지막 방어선이다.
- ③ `Lottery.create`가 값 관계(`LotterySettings.validate`)와 시점(`requireBeforeSetupDeadline`)을 검사한다. 규칙을 엔티티(도메인)에 두면 POST·PATCH 어디서 만들든 같은 규칙이 적용된다.
- ④ 좌석 전환 SQL(concert 저장소):
```sql
UPDATE schedule_seats SET grade = 'LOTTERY', ...
WHERE id IN (SELECT id FROM schedule_seats
             WHERE schedule_id = :scheduleId AND grade = :sourceGrade AND status = 'AVAILABLE'
             ORDER BY id LIMIT :seatCount FOR UPDATE)
```
  바꾼 행 수가 요청보다 적으면 `LOTTERY_SEATS_NOT_ENOUGH`(409)를 던지고, 예외 때문에 트랜잭션 전체가 롤백돼 이미 바꾼 좌석도 원래대로 돌아간다. "전부 아니면 전무"를 트랜잭션 하나로 얻는다.

### 2-4. 수정(PATCH) — `Lottery.update`와 좌석 조정
```java
public void update(LotterySettings settings, ScheduleTimes schedule, Instant now) {
    requireBeforeSetupDeadline(schedule.openAt(), entryStartAt, now);          // 지금 값으로 먼저
    settings.validate(schedule);
    requireBeforeSetupDeadline(schedule.openAt(), settings.entryStartAt(), now); // 새 값으로도
    apply(settings);
}
```
- **왜 두 번 확인하나**: 이미 응모가 시작된 추첨에 "응모 시작을 내일로 미루는" 수정을 보내면, 새 값만 보고는 통과해 버린다. 지금 값으로 먼저 막아야 한다. 변이 테스트로 이 줄을 지우면 "응모가 이미 시작한 추첨은 409" 테스트가 실패하는 것을 확인했다.
- 좌석 조정(`adjustSeats`): 등급이 바뀌면 전부 되돌리고 새 등급에서 다시 뗀다. 같은 등급이면 늘린 만큼 더 떼고, 줄인 만큼 **ID가 큰 것부터** 되돌린다(`ORDER BY id DESC`). 남는 추첨석이 ID 순으로 이어져야 옆자리 배정(ISE-84)에 유리하기 때문이다.
- 수정은 `findScheduleIdById`(값만 조회) → 회차 잠금 → `findById` 순서다. 엔티티를 먼저 읽으면 JPA 1차 캐시에 옛 값이 올라가, 잠금 뒤 다시 읽어도 다른 수정이 커밋한 최신 값을 못 볼 수 있다.
- **동시 수정 방식(11/2 결정)**: `version` 컬럼(낙관적 락) 대신 회차 행 잠금. 이미 잠그는 구조라 스키마를 늘리지 않았다. 감수한 점: 두 운영자가 각자 오래된 화면을 보고 고치면 나중 요청이 이긴다.

### 2-5. 그 밖의 규칙
- 설정·수정 시점: `now < LEAST(open_at, entry_start_at)`. 일반 오픈 뒤에 좌석을 떼면 이미 팔린 좌석과 다투고, 응모 시작 뒤 바꾸면 응모자 조건이 달라진다.
- `draw_at + 60분 <= starts_at`(11/2 결정): 추첨 뒤 결제·승계·종결에 최장 60분(D14)이 걸리므로 공연 시작 전에 끝나야 한다. 경계값(59분 전 400, 60분 전 201)을 테스트했다.
- 저트래픽 시간대(03:00~05:00 서울, `[가정]`) 밖이면 막지 않고 응답 `warnings`에 경고를 담는다(D10 운영 정책).
- `LotteryErrorCode`는 domain 패키지에 있다. 엔티티가 이 코드를 던지는데 application에 두면 domain → application 역방향 의존이 생긴다(ISE-47에서 고친 문제와 같다).

### 2-6. 테스트가 증명하는 것
| 테스트 | 증명 |
|---|---|
| `LotteryMigrationTest` 7개 | 제약 이름이 카탈로그에 있고, 회차당 2개·LOTTERY 원천 등급·8바이트 시드·기간 역전·취소 뒤 재응모가 DB에서 막힌다 |
| `AdminLotteryApiTest` 19개 | 설정 성공·경고·좌석 부족 롤백·중복·시점·검증 400·404·403, 수정의 늘리기·줄이기(ID 큰 것부터)·등급 변경·롤백·응모 시작 뒤 409 |
| 변이 테스트 | 좌석 부족 확인 삭제, 권장 시간대 변경, 60분 경계, 되돌리기 오름차순, 수정 마감 사전 확인 삭제 → 각각 해당 테스트만 실패 |

### 2-7. 리뷰 의견
- 좋음: 설정을 한 트랜잭션으로 묶고 회차 잠금으로 직렬화해, 경합 시나리오를 단순하게 만들었다.
- 주의: 좌석 전환 중 일반 선점과 겹치면 `FOR UPDATE`로 기다린 뒤 HELD가 된 행은 빠지는데, `LIMIT`이 빈자리를 다시 채우지 않아 409가 날 수 있다. 일반 오픈 전에만 설정할 수 있어 실제로는 드물다(후속 A, M4 동시성 트러블슈팅).
- 주의: 테스트 DB 컨테이너가 실행 사이에 남아 있어, 머지 전 마이그레이션을 고치면 Flyway checksum 불일치로 로컬 테스트가 무더기로 실패한다(TS-28, 10-8).

### 2-8. 예상 질문
- "회차당 추첨 1개를 왜 애플리케이션 확인과 UNIQUE 둘 다로 막나?" → 확인은 친절한 409를 주고, UNIQUE는 동시 요청이나 버그로 확인이 뚫렸을 때의 최종 방어다. 여기서는 회차 잠금 덕에 확인이 뚫릴 일이 없어 UNIQUE는 실제로 거의 쓰이지 않는다.
- "좌석이 모자라면 이미 바꾼 좌석은 어떻게 되돌리나?" → 별도 보상 코드 없이, 런타임 예외로 트랜잭션이 롤백되면서 UPDATE가 취소된다.

---

## 3. ISE-86 · PR #195 — 응모·취소·결과·공개 조회 API

### 3-1. 무엇을 만들었나
`POST /api/v1/lotteries/{id}/entries`(응모, 201), `DELETE …/entries/me`(취소), `GET …/entries/me`(내 결과), `GET /api/v1/lotteries/{id}`(공개 규칙). 추첨 스케줄러가 쓸 `findDrawEntrants`(ENTERED만)도 여기서 만들었다.

### 3-2. 핵심: 기간 확인과 INSERT를 한 문장으로
```sql
INSERT INTO lottery_entries (lottery_id, request_id, user_id, quantity, status, ...)
SELECT l.id, :requestId, :userId, :quantity, 'ENTERED', ...
FROM lotteries l
WHERE l.id = :lotteryId AND l.status = 'OPEN'
  AND now() >= l.entry_start_at AND now() < l.entry_end_at
  AND :quantity <= l.max_per_entry
ON CONFLICT DO NOTHING
RETURNING id
```
- **`INSERT ... SELECT ... WHERE`**: 조건이 맞을 때만 한 행을 넣는다. "기간을 확인하고(SELECT) 넣는다(INSERT)"를 따로 하면 그 사이에 마감될 수 있지만, 한 문장이면 같은 시점에 판단한다. 시각은 **DB `now()` 하나**라 서버마다 시계가 달라도 마감 판정이 어긋나지 않는다.
- **`ON CONFLICT DO NOTHING`**: 유니크 위반(같은 사용자 두 번째 응모, 같은 멱등키)을 예외 대신 "0행 삽입"으로 받는다. PostgreSQL은 제약 위반 예외가 나면 그 트랜잭션을 중단 상태로 만들어 이후 쿼리를 못 하게 한다. 0행으로 받아야 같은 트랜잭션에서 "왜 거절됐나"를 다시 읽어 409 `LOTTERY_ALREADY_ENTERED`·`LOTTERY_NOT_OPEN`·400을 가릴 수 있다.
- 동시성: 같은 사용자가 동시에 두 번 응모하면, 뒤 INSERT는 유니크 인덱스에서 앞 트랜잭션의 커밋을 기다린 뒤 충돌로 0행이 된다. 테스트는 사용자 5명으로 반복해 매번 201 하나·409 하나를 확인했다.

### 3-3. 서비스 흐름(`LotteryEntryService.enter`)
```java
var replay = findReplay(...);                // ① 같은 멱등키가 이미 있나(같은 내용이면 그대로 돌려줌, 다르면 409)
if (replay.isPresent()) return replay.get();
var insertedId = lotteryEntryRepository.insertIfOpen(...);   // ② 조건부 INSERT
if (insertedId.isPresent()) return findById(...);
return findReplay(...)                       // ③ 같은 키로 동시에 온 앞 요청이 방금 커밋했을 수 있다
        .orElseThrow(() -> new BusinessException(rejectReason(...)));  // ④ 거절 이유 판별
```
- **멱등성**: 같은 `Idempotency-Key`로 다시 보내면 처음 결과를 그대로 준다. 네트워크 재시도로 응모가 두 번 생기지 않는다. 별도 `idempotency_records` 테이블 대신 응모 행의 `request_id UNIQUE`를 쓴다(선점 API와 같은 방식).
- 취소: `UPDATE ... SET status='CANCELED' WHERE ... status='ENTERED' AND l.status='OPEN' AND now() < l.entry_end_at`. 이미 취소한 응모에 다시 보내면 같은 결과를 준다(DELETE의 멱등).
- 취소해도 행을 지우지 않는다. 그래야 `UNIQUE(lottery_id, user_id)`가 재응모를 막는다(D5).

### 3-4. 리뷰 의견
- 좋음: 마감 판정을 DB 한 곳에 모아 시계 문제를 없앴다.
- 시계 기준이 두 개다: 응모는 DB `now()`, 운영자 설정은 앱 시계. 마감 직전 경합이 응모에만 있어 그렇게 뒀다(`details.md`에 이유 기록).
- 남은 것(ISE-143으로 이관): 결과 조회의 "결제 확인 중"·"환불 예정" 안내, Gateway가 공개 조회를 아직 로그인 필요로 막는 문제.

### 3-5. 예상 질문
- "`SELECT` 후 `INSERT`와 `INSERT ... SELECT`의 차이는?" → 앞은 두 문장 사이에 상태가 바뀔 수 있다(TOCTOU). 뒤는 한 문장이라 판단과 쓰기가 같은 시점이다.
- "왜 예외 대신 `ON CONFLICT`?" → PostgreSQL에서 제약 위반 예외는 트랜잭션을 중단시켜 같은 트랜잭션에서 후속 조회가 불가능하다.

---

## 4. ISE-87 · PR #207 — 마감·추첨 스케줄러와 추첨 실행

### 4-1. 무엇을 만들었나
- 마감: `UPDATE lotteries SET status='CLOSED' WHERE status='OPEN' AND entry_end_at <= now()` 한 문장(10초 주기)
- 추첨(10초 주기): `LotteryDrawService.draw` — 추첨 하나 = 트랜잭션 하나
- 지표: `lottery.draw.duration`, `lottery.draw.failures`, `lottery.draw.consecutive.failures`

### 4-2. 추첨 코드
```java
@Transactional(timeout = 10)
public boolean draw(Long lotteryId) {
    if (!lotteryRepository.markDrawn(lotteryId)) return false;   // ① CLOSED → DRAWN 조건부 UPDATE
    var lottery = lotteryRepository.findById(lotteryId).orElseThrow();
    var ranked = DrawRanker.rank(lottery.getSeed(), lotteryEntryRepository.findDrawEntrants(lotteryId)); // ② 순위
    var allocation = SeatAllocator.draw(ranked,
            lotterySeatPort.findLotterySeats(lottery.getScheduleId()), lottery.getWaitlistCount());       // ③ 배정
    ... results(WON·WAITLISTED, 순위 포함)
    lotteryEntryRepository.saveDrawResults(results);             // ④ JDBC 배치 UPDATE
    lotteryEntryRepository.markRemainingLost(lotteryId);         // ⑤ 남은 ENTERED → LOST 한 문장
    return true;
}
```
- ① **한 번만 실행**: `UPDATE ... SET status='DRAWN' WHERE id=? AND status='CLOSED' AND draw_at <= now()`가 1행일 때만 진행한다. 서버가 여러 대라 같은 추첨을 동시에 집어도, 행 잠금 때문에 뒤 트랜잭션은 앞이 끝난 뒤 이미 DRAWN을 보고 0행이 된다. 변이 테스트: `status='CLOSED'`를 빼면 "두 번 부르면 한 번만 반영" 테스트가 실패한다.
- `lock_timeout` 3초: `SELECT set_config('lock_timeout', '3s', true)`로 이 트랜잭션에만 잠금 대기 상한을 건다. `@Transactional(timeout = 10)`은 전체 상한이다. 넘으면 롤백되고 상태가 CLOSED로 남아 다음 주기에 다시 시도한다.
- ② 순위(ISE-84): 응모마다 `SHA-256(seed ‖ entryId)`를 구해 오름차순 정렬. 입력 순서와 무관하게 같은 시드면 같은 결과라, 중간에 실패해 다시 돌려도 결과가 같다(재현성).
- ④ **쓰기 줄이기(D8)**: 당첨·예비(수천 건)만 순위와 함께 JDBC 배치로 쓰고, 낙첨(수만 건)은 한 문장으로 바꾸며 순위를 저장하지 않는다(11/2 결정). `JdbcTemplate.batchUpdate`는 JPA 트랜잭션이 연 같은 커넥션을 쓰므로 같이 롤백된다.
- 추첨 시점에 계산한 좌석 배정은 저장하지 않는다. 실제 좌석은 배정 워커가 예매를 만들 때 고른다(ISE-90).

### 4-3. 측정
응모 30,000·좌석 2,000·예비 1,000, 8회 446~4,600ms. 모두 상한 10초 안이지만 0.5초와 4.5초로 갈리는 원인은 미확인이다(`numbers-and-measurement.md` 3-1). 측정 시나리오 1(마감 직전 초당 100건)과 D3 판단은 부하 도구·배포 환경이 필요해 ISE-94(M4)로 옮겼다.

### 4-4. 스케줄러 설계
- `@ConditionalOnProperty(name = "lottery.scheduler.enabled", matchIfMissing = true)`: 테스트 프로필은 끈다. 테스트는 서비스를 직접 불러 시각과 데이터를 통제한다.
- 추첨 하나가 실패해도 다음 추첨은 계속한다(try/catch, 실패 수·연속 실패 지표).

### 4-5. 예상 질문
- "추첨을 청크로 나누지 않고 한 트랜잭션으로 한 이유?" → 전부 반영되거나 전부 안 되는 단순함(재시도도 쉬움)을 얻고, 비용(긴 트랜잭션)은 쓰기 줄이기·시간 상한·측정으로 관리한다. 측정에서 상한을 넘거나 연속 시간 초과가 나면 청크(D8 B안)로 바꾼다.
- "여러 서버가 동시에 추첨하면?" → 조건부 UPDATE + 행 잠금으로 한 서버만 1행을 얻는다.

---

## 5. ISE-58 · PR #208 — Gateway 입장 토큰 검증 필터

### 5-1. 무엇을 만들었나
좌석 조회 `GET /api/v1/schedules/{id}/seats`와 선점 `POST /api/v1/schedules/{id}/reservations` 앞에서 `X-Entry-Token`을 검사하는 WebFlux `GlobalFilter`. 실패하면 403 `ENTRY_TOKEN_INVALID`.

### 5-2. 구성
| 클래스 | 역할 |
|---|---|
| `EntryTokenPolicy` | 대상 경로 목록. `PathPattern.matchAndExtract`로 경로의 `{scheduleId}`를 꺼낸다 |
| `EntryTokenFilter`(order -90) | 토큰 디코딩 + `sub == X-User-Id` + claim `scheduleId == 경로 회차` |
| `GatewayConfiguration.entryTokenDecoder` | `EntryTokenDecoder`를 빈으로 등록 |
| `config-repo/gateway.yml`, `compose.yml` | `ENTRY_TOKEN_SECRET` 주입 |

```java
var jwt = decoder.decode(token);                       // 서명·typ·aud·exp·필수 claim
Object scheduleId = jwt.getClaim(EntryTokenClaims.SCHEDULE_ID);
return userId.equals(jwt.getSubject())                 // 다른 사람 토큰 빌려 쓰기 금지(ADR 0015)
        && scheduleId instanceof Long id && id == Long.parseLong(pathScheduleId); // 다른 회차 금지
```

### 5-3. 알아야 할 개념
- **필터 순서**: 인증 필터(-100)가 먼저 돈다. 그 필터는 클라이언트가 보낸 `X-User-Id`를 지우고 검증한 Access 토큰의 사용자로 다시 넣는다. 그래서 입장 토큰 필터(-90)가 읽는 `X-User-Id`는 믿을 수 있다.
- **빈 등록이 필요했던 이유**: `EntryTokenDecoder`는 common 모듈의 평범한 클래스(`@Component` 없음)라 Spring이 자동으로 만들지 않는다. Gateway 설정에서 키를 받아 `new`로 만들어 빈으로 등록해야 필터가 주입받는다. 키가 없거나 32바이트 미만이면 생성자가 예외를 던져 Gateway가 기동하지 않는다(Fail-fast).
- **경로 판정을 `PathPattern`으로**: Gateway 라우터와 같은 방식으로 파싱된 경로를 비교한다. `;x=1` 같은 matrix parameter나 `%73eats` 같은 인코딩으로 우회하지 못한다(테스트 포함).
- **JWT 검증 항목**: 서명(HS256, 같은 비밀키로 위조 불가), `typ=entry`, `aud=gateway`(결제 토큰을 넣어도 거절, E14), `exp`(만료), 필수 claim.

### 5-4. 테스트가 증명하는 것
토큰 없음·위조·만료·다른 회차·다른 사용자·결제 토큰·형식 오류 → 403, 정상 → 통과, 대기열·추첨 응모·공연 조회 → 토큰 없이 통과, 경로 변형 → 차단. 변이 테스트: 사용자 비교·회차 비교를 각각 지우면 해당 테스트만 실패.

### 5-5. 예상 질문
- "Gateway가 왜 Redis 없이 서명만으로 검증하나?" → 좌석 조회·선점은 오픈 순간 트래픽이 몰리는 경로다. 매 요청 Redis 조회를 넣으면 Redis가 병목·장애 지점이 된다(SA 9-5). 서명 검증은 CPU만 쓴다. 대신 발급 뒤 취소(폐기)는 할 수 없고, 짧은 만료(10분)로 위험을 줄인다.

---

## 6. ISE-88 · PR #209 — 시스템 선점과 결제 결과 처리기 인터페이스

### 6-1. 무엇을 만들었나
- `SystemSeatHoldService`(seathold): 당첨자 예매를 만드는 선점. 입장 토큰·대기열·판매 기간·결제 토큰 없이, `LOTTERY` 좌석만, 선점 30분, `source = LOTTERY`.
- `SystemHoldPort` + `SeatHoldSystemHoldAdapter`(lottery): 추첨이 시스템 선점을 부르는 통로.
- `LotteryPaymentResultHandler`: `onPaid`·`onFailed(reasonCode)`·`onExpired`. 예매 흐름이 추첨에 결제 결과를 알리는 통로.
- `Reservation.holdForLottery`: 출처만 LOTTERY인 예매 생성.

### 6-2. 핵심 코드
```java
@Transactional(propagation = Propagation.MANDATORY)
public SystemHoldResult holdLotterySeats(Long userId, Long scheduleId, List<Long> seatIds) {
    ... 매수 한도 확인·증가 → holdAvailableLotterySeats(…) 행 수 확인 → 예매·예매 좌석·이력 저장
}
```
```sql
UPDATE ScheduleSeat s SET s.status = HELD ...
 WHERE s.schedule.id = :scheduleId AND s.id IN :ids AND s.status = AVAILABLE
   AND s.grade = :lottery          -- 일반 선점과 반대 조건
```

### 6-3. 알아야 할 개념
- **트랜잭션 전파 `MANDATORY`**: 이미 열린 트랜잭션 안에서만 부를 수 있고, 없으면 `IllegalTransactionStateException`. 배정 워커의 "예매 HELD → 응모 연결"이 반드시 한 트랜잭션이 되게 강제한다. `REQUIRED`(기본)였다면 실수로 트랜잭션 밖에서 불렸을 때 예매만 커밋되고 응모 연결은 빠질 수 있다.
- **잠금 순서**: 일반 선점과 같이 "매수 → 좌석 → 예매" 순서다. 두 트랜잭션이 서로 다른 순서로 자원을 잡으면 서로 기다리다 교착(deadlock)이 날 수 있다. 순서를 통일하면 교착이 생기지 않는다.
- **포트·어댑터와 ADR 0034**: `reservation`·`concert`는 `seathold`를 import하지 못하므로, 어댑터를 lottery 쪽에 두고 방향을 lottery → seathold로 했다.

### 6-4. 범위를 나눈 이유(11/2 결정)
처리기를 부를 `payment.result` 소비자(ISE-73·74)와 만료 스케줄러(ISE-8)가 아직 없다. 연결은 ISE-143(M3)으로 옮겼다. 이 PR이 머지되며 ISE-88은 자동 Done이 됐고, 남은 항목은 후속 이슈로 옮기는 규칙을 git-workflow 6-3에 남겼다.

### 6-5. 예상 질문
- "`MANDATORY`와 `REQUIRED`, `REQUIRES_NEW`의 차이?" → REQUIRED: 있으면 참여, 없으면 새로. REQUIRES_NEW: 항상 새로(바깥과 독립 커밋). MANDATORY: 반드시 바깥 트랜잭션이 있어야 함. 여기서는 "독립 커밋되면 안 되는" 작업이라 MANDATORY.

---

## 7. ISE-90 · PR #210 — 배정 워커와 결제 결과 처리기

### 7-1. 배정 워커(`LotteryAssignmentService.assignNext`)
```java
@Transactional
public Optional<Long> assignNext() {
    var next = lotteryEntryRepository.lockNextUnassignedWinner();   // ① SKIP LOCKED로 1명 집기
    if (next.isEmpty()) return Optional.empty();
    var seatIds = pickSeats(winner);                                // ② 빈 추첨석 중 고르기(2매는 옆자리 우선)
    try {
        var hold = systemHoldPort.hold(...);                        // ③ 시스템 선점(MANDATORY)
        lotteryEntryRepository.linkReservation(entryId, hold.reservationId()); // ④ 연결, 시도 +1
    } catch (BusinessException e) {
        throw new AssignmentRejected(entryId, e.getErrorCode().code());       // ⑤ 거절 → 롤백
    }
    return Optional.of(entryId);
}
```
```sql
SELECT e.id, ... FROM lottery_entries e JOIN lotteries l ON l.id = e.lottery_id
WHERE e.status = 'WON' AND e.reservation_id IS NULL AND l.status = 'DRAWN'
ORDER BY e.draw_rank, e.id LIMIT 1
FOR UPDATE OF e SKIP LOCKED
```
- **`SKIP LOCKED`**: 다른 워커가 잠근 행은 기다리지 않고 건너뛴다. 워커가 여러 대여도 같은 당첨자를 두 번 배정하지 않고, 한 명이 늦어도 나머지가 막히지 않는다(작업 큐 패턴).
- **1명 = 1트랜잭션**: 도중에 죽으면 그 1명만 롤백되고, 응모는 "WON·예매 없음"으로 남아 다음 주기가 다시 집는다. 테스트: 배정 트랜잭션을 강제로 롤백한 뒤 같은 응모가 다음 호출에서 배정되는 것을 확인했다.
- **거절 처리**: 매수 한도 초과·남은 자리 없음은 다시 해도 같은 결과라 FAILED로 남겨야 한다. 그런데 같은 트랜잭션에서 FAILED를 쓰면 예외로 롤백될 때 함께 사라진다. 그래서 예외로 배정 트랜잭션을 롤백한 뒤(매수·좌석 변경 취소), 스케줄러가 **새 트랜잭션**에서 `markRejected`로 FAILED를 기록한다.

### 7-2. 결제 결과 처리기(`LotteryPaymentResultService`)
```java
public void onFailed(Long reservationId, String reasonCode) {
    if (NOT_SENT.equals(reasonCode)
            && lotteryEntryRepository.releaseForRetry(reservationId, MAX_PAYMENT_ATTEMPTS)) {
        return;                                    // 연결만 비움 → 배정 워커가 새 예매로 재시도
    }
    lotteryEntryRepository.markFailed(reservationId, reasonCode);
}
```
- 모든 전이가 "이 예매에 연결된 **WON** 응모만" 바꾸는 조건부 UPDATE다. 같은 결과 이벤트가 두 번 와도 두 번째는 0행이라 바뀌지 않는다(멱등 소비자). 끝난 응모(PAID·FAILED)에 다른 결과가 와도 바뀌지 않는다.
- `NOT_SENT` 재시도(D12, ADR 0038): 결제 요청이 PG에 가지도 못한 우리 책임 실패만 다시 시도한다. 시도 2회 미만 + 승계 마감(추첨 후 30분) 전일 때만. 같은 예매로 재결제하지 않고 **새 예매**로 한다(결제는 예매당 1건, ADR 0026).
- 변이 테스트: 최대 시도를 3으로 바꾸면 NOT_SENT 테스트 실패, PAID 전이에서 `status='WON'`을 빼면 "두 번 와도 한 번만" 테스트 실패.

### 7-3. 리뷰 의견
- 남은 위험: 실패한 이전 예매의 좌석·매수 반환은 예매 흐름(결제 결과 소비자) 몫이다. 반환이 늦으면 재시도·승계 배정이 `NO_SEAT`로 떨어질 수 있다.
- 매수 한도는 "읽고 더하기"라 같은 사용자 동시 요청 경합(TS-2)이 일반 선점과 같이 남아 있다. 배정은 당첨자 1명당 1트랜잭션이라 같은 사용자 동시 호출이 드물다.

### 7-4. 예상 질문
- "`SKIP LOCKED`와 `NOWAIT`의 차이?" → NOWAIT는 잠긴 행을 만나면 즉시 에러, SKIP LOCKED는 그 행을 결과에서 빼고 나머지를 준다. 작업 큐에는 SKIP LOCKED가 맞다.
- "멱등 소비자는 왜 필요한가?" → Kafka는 최소 한 번 전달이라 같은 메시지가 다시 올 수 있다. 조건부 UPDATE로 두 번째 처리를 무해하게 만든다.

---

## 8. ISE-93 · PR #211 — 승계 워커와 승계 마감

### 8-1. 코드
```java
@Transactional
public int succeed(Long lotteryId) {
    var seatCount = lotteryRepository.lockForSuccession(lotteryId);   // 추첨 행 FOR UPDATE SKIP LOCKED + 마감 조건
    if (seatCount.isEmpty()) return 0;
    int remaining = seatCount.get() - lotteryEntryRepository.sumTakenSeats(lotteryId); // 좌석 수 - (WON·PAID 매수)
    if (remaining <= 0) return 0;
    var selected = SuccessionPlanner.select(lotteryEntryRepository.findWaitlist(lotteryId), remaining);
    return lotteryEntryRepository.promoteToWon(ids(selected));        // WAITLISTED일 때만 WON
}
```
- **추첨 행 잠금으로 직렬화(D13)**: "남은 좌석 계산 → 예비자 승격"은 읽고-쓰는 작업이라, 두 워커가 동시에 하면 둘 다 같은 남은 좌석을 보고 초과 당첨시킬 수 있다. 추첨 행을 잠근 워커만 계산하게 해 막는다. 다른 워커는 `SKIP LOCKED`로 건너뛴다.
- **마감 조건을 잠금 쿼리에**: `now() < draw_at + 30분`을 같은 문장에 둬서 마감 뒤에는 잠금 자체를 얻지 못한다.
- **매수 건너뛰기**: 남은 좌석보다 매수가 많은 예비자는 건너뛰고 예비자로 남긴다. 다음 승계 때 다시 기회를 얻는다(`SeatAllocator.succeed`와 같은 규칙).
- 승계 마감(10초 주기): 마감이 지난 DRAWN 추첨의 남은 WAITLISTED를 한 문장으로 LOST.

### 8-2. 테스트의 한계와 대응
"워커 2개 동시 실행" 테스트는 경합이 우연히 비켜 가면 잠금을 빼도 통과할 수 있다. 그래서 다른 스레드가 추첨 행을 `FOR UPDATE`로 잡고 있는 상태를 **직접 재현**하는 테스트를 따로 두었다. 잠금을 빼면 이 테스트가 확실히 실패한다(변이 테스트로 확인). 동시성 테스트는 "우연히 통과"를 경계해야 한다.

### 8-3. 예상 질문
- "왜 좌석 수로 세나, 실제 빈 좌석을 세지 않고?" → 승계는 "몇 명을 더 당첨시킬지"만 정하고 좌석은 배정 워커가 고른다. 대신 실패한 예매의 좌석 반환이 늦으면 배정이 NO_SEAT가 될 수 있다(7-3).

---

## 8-1. ISE-91 · PR #224 — 종결 스케줄러와 추첨석 반환

### 8-1-1. 무엇을 만들었나
- `LotteryFinishService.finish`: 끝난 추첨을 `DRAWN → DONE`으로 바꾸고, 같은 트랜잭션에서 그 회차의 `LOTTERY` 좌석을 상태(AVAILABLE·HELD·SOLD)와 상관없이 `source_grade`로 되돌린다(D9·D14).
- `LotteryFinishScheduler`(1분): 후보마다 `finish`를 부르고, 실패해도 다음 추첨은 계속한다. 지표 `lottery.seats.remaining`(전체 남은 추첨석 수)을 1분마다 갱신한다.
- concert 저장소 `revertAllFromLottery`·`countAllLotterySeats`, lottery 저장소 `findFinishDueIds`·`lockForFinish`·`markDone`·`hasUnsettled`, 포트 `LotterySeatPort`에 같은 이름의 메서드 2개.

### 8-1-2. 코드
```java
@Transactional
public Optional<Integer> finish(Long lotteryId) {
    var lottery = lotteryRepository.lockForFinish(lotteryId);            // ① 추첨 행 FOR UPDATE SKIP LOCKED
    if (lottery.isEmpty() || lotteryEntryRepository.hasUnsettled(lotteryId)) {  // ② 잠금 뒤 새 문장으로 재확인
        return Optional.empty();
    }
    lotteryRepository.markDone(lotteryId);                               // ③ DRAWN일 때만 DONE
    return Optional.of(lotterySeatPort.revertAllFromLottery(              // ④ 같은 트랜잭션에서 반환
            lottery.get().getScheduleId(), lottery.get().getSourceGrade(), null));
}
```
```sql
-- ④ 상태 조건 없이 등급만 되돌린다
UPDATE schedule_seats SET grade = :sourceGrade, updated_at = now(), updated_by = CAST(:actorId AS BIGINT)
WHERE schedule_id = :scheduleId AND grade = 'LOTTERY' AND deleted_at IS NULL
```
- ① 승계 워커(8장)와 **같은 추첨 행 잠금**을 쓴다. 승계가 진행 중이면 종결은 건너뛰고 다음 주기에 다시 본다. 반대로 종결이 잡고 있으면 승계가 건너뛴다. 그래서 "남은 좌석 계산 → 예비자 승격"과 "좌석 반환"이 엇갈리지 않는다.
- ② **잠금 뒤 다시 확인하는 이유**: 후보 목록(`findFinishDueIds`)은 잠금 없이 고른다. 고른 뒤 잠금을 얻기 전에 승계가 커밋됐을 수 있다. PostgreSQL 기본 격리 수준 READ COMMITTED는 **문장마다 새 스냅샷**을 쓰므로, 잠금을 얻은 뒤 새 문장(`hasUnsettled`)으로 물으면 그 사이 커밋된 WON도 보인다. 후보 조회 결과만 믿으면 이 경합을 놓친다.
- ③④ 한 트랜잭션이라 "DONE인데 좌석이 아직 LOTTERY"인 상태가 생기지 않는다. 롤백되면 DONE과 반환이 함께 취소된다(테스트로 확인). `actorId`는 시스템 작업이라 `null`이다.

### 8-1-3. 판단: 종결 조건에 WAITLISTED를 더했다
| 선택지 | 결과 |
|---|---|
| A. 이슈 문구대로 "미종결 WON이 없음" | 당첨이 모두 실패하고 승계 전이면 바로 종결된다. 이후 예비자가 WON이 돼도 좌석이 이미 S석으로 돌아가 배정이 `NO_SEAT`로 실패한다 |
| B. WON·WAITLISTED가 모두 없음(선택) | 승계될 수 있는 예비자가 있으면 기다린다. 승계 마감(추첨 후 30분) 뒤에는 예비자가 LOST가 되므로 종결 상한 T+60분(D14)은 그대로다 |

감수한 점: 좌석이 이미 모두 팔려(PAID) 승계할 자리가 없어도, 예비자가 남아 있으면 승계 마감까지 최대 30분 종결이 늦어진다. 남은 추첨석이 없으니 일반 판매에 손해는 없다. 설계 문서 `details.md` 4절 종결 행과 Linear 이슈 범위에 반영했다.

### 8-1-4. HELD·SOLD 추첨석도 되돌리는 이유(D9)
- SOLD: 팔린 추첨석은 `reservations.source = 'LOTTERY'`로 구분되므로 좌석 등급이 S로 바뀌어도 기록은 남는다.
- HELD: 결제 실패한 예매의 좌석이 아직 풀리지 않았을 수 있다. 등급만 S로 바꿔 두면, 나중에 예매 흐름이 좌석을 AVAILABLE로 풀 때 바로 일반 판매에 나온다. AVAILABLE만 되돌리면 이 좌석은 영원히 LOTTERY로 남는다.

### 8-1-5. 지표 설계
`lottery.seats.remaining`은 모든 회차의 남은 추첨석 수다. 진행 중인 추첨이 없는데 0이 아니면 반환이 빠졌다는 신호다(`details.md` 5절 "종결 반환 누락").
- 왜 Prometheus 수집 때마다 DB를 세지 않고 스케줄러가 1분마다 값을 넣나: 수집 주기(15초)마다 좌석 테이블을 세면 부하를 통제하기 어렵다. 종결 주기와 같은 1분이면 충분하다.
- 왜 "DONE 추첨 회차의 남은 추첨석"이 아니라 전체 수인가: 그렇게 세려면 좌석 테이블과 추첨 테이블을 조인해야 하는데, 모듈 경계(lottery 쿼리는 lottery 테이블만, 좌석은 concert 저장소만)를 넘는다. 전체 수로 단순하게 두었다.

### 8-1-6. 테스트가 증명하는 것
| 테스트(`LotteryFinishTest`) | 증명 |
|---|---|
| 끝나지 않은 응모가 없으면 DONE, 추첨석 모두 원래 등급 | AVAILABLE·HELD·SOLD 추첨석 4개가 S로, 좌석 상태는 그대로, 일반 좌석은 영향 없음, 두 번째 호출은 빈 결과(멱등) |
| 결제가 끝나지 않은 당첨이 남아 있으면 종결하지 않음 | 완료 기준 2 |
| 승계될 수 있는 예비자가 남아 있으면 종결하지 않음 | 8-1-3 판단 |
| 다른 워커가 추첨 행을 잡고 있으면 건너뜀 | 잠금 공유. 잠금을 기다리지 않고 빈 결과, 풀린 뒤 종결 |
| 종결 트랜잭션이 롤백되면 DONE과 반환이 함께 취소 | 완료 기준 1(같은 트랜잭션) |

변이 테스트: `hasUnsettled` 확인 삭제 → WON·WAITLISTED 테스트 2개 실패, 조건에서 WAITLISTED 제외 → 예비자 테스트 1개 실패. reservation-service 전체 202개 통과.

### 8-1-7. 리뷰 의견
- 남은 위험: 종결 뒤 늦게 도착한 결제 결과(예: 만료 뒤 승인)는 응모 상태를 바꾸지 못한다(`WHERE status='WON'`). 환불은 예매 흐름의 `RefundRequested` 경로가 맡는다(ISE-143에서 E2E 확인).
- 후보 조회 `findFinishDueIds`는 1분마다 DRAWN 추첨 수만큼 `NOT EXISTS`를 돈다. 동시에 DRAWN인 추첨은 몇 개뿐이라 문제없다고 보았다(측정 안 함).

### 8-1-8. 예상 질문
- "종결과 승계가 동시에 돌면?" → 같은 추첨 행을 `FOR UPDATE SKIP LOCKED`로 잡으므로 한쪽만 진행하고 다른 쪽은 다음 주기로 미룬다.
- "후보를 고를 때 조건을 확인했는데 왜 또 확인하나?" → 후보 조회는 잠금 없이 하므로 그 뒤 승계가 커밋될 수 있다. READ COMMITTED는 문장마다 새 스냅샷이라 잠금 뒤 새 문장으로 확인하면 최신 상태를 본다.
- "팔린 좌석까지 등급을 바꿔도 되나?" → 등급은 판매 구분용이고 판매 기록은 예매의 `source`가 가진다. 바꿔야 종결 뒤 풀리는 좌석이 일반 판매로 돌아간다.

---

## 9. ISE-141 · PR #216, ISE-144 — 이슈·PR 메타데이터 규칙과 일괄 정리, 추첨 마일스톤 재배치

- GitHub 마일스톤 M1~M4를 Linear와 같은 이름·기간으로 만들고, 팀 전체 PR·이슈 185건의 담당자·라벨·마일스톤 빈칸을 Linear 기준으로 채웠다(덮어쓰지 않음).
- 확인한 사실: Linear 이슈는 GitHub 이슈로 동기화되며 담당자·라벨은 따라오지만 **마일스톤은 따라오지 않는다**. PR 메타데이터는 자동으로 채워지지 않는다. PR을 머지하면 연결된 Linear 이슈가 **남은 체크리스트와 상관없이 자동 Done**이 된다.
- 규칙은 `docs/guides/git-workflow.md` 6절에 있다. 부분 완료로 자동 Done이 되면 이슈를 다시 열지 않고 후속 이슈로 옮긴다(ISE-88 → ISE-143).
- 추첨 재배치(팀 합의, 공통 판단 기록): 추첨을 Should → Must로 올려 기본 기능 M2, 통합 테스트 M3, 측정·수치 기반 개선 M4. SA 4장·5-2·6-7·11-4와 `milestones.md`를 고쳤다.

---

## 10. 여러 이슈에 걸친 개념

### 10-1. 조건부 UPDATE(상태 전이를 DB가 지키게)
`UPDATE ... SET status='B' WHERE id=? AND status='A'` 형태. 갱신 행 수(0 또는 1)가 "내가 전이를 해냈는가"를 알려 준다. 애플리케이션에서 읽고(A인지 확인) 쓰면 그 사이 다른 트랜잭션이 바꿀 수 있지만, 한 문장이면 행 잠금 아래에서 판단과 쓰기가 같이 일어난다. 오늘 거의 모든 전이(마감·추첨·배정 연결·PAID·FAILED·승계·취소)가 이 방식이다. 멱등성과 "한 번만 실행"을 함께 얻는다.

### 10-2. 행 잠금: `FOR UPDATE`, `SKIP LOCKED`, `lock_timeout`
| 도구 | 동작 | 오늘 쓴 곳 |
|---|---|---|
| `SELECT ... FOR UPDATE` | 읽은 행을 트랜잭션 끝까지 잠금. 다른 쓰기·`FOR UPDATE`는 기다림 | 회차 잠금(설정·수정), 좌석 전환 |
| `FOR UPDATE SKIP LOCKED` | 잠긴 행은 건너뜀 | 배정 워커, 승계 워커 |
| `lock_timeout` | 잠금 대기 상한. 넘으면 에러 | 추첨 트랜잭션(3초) |
| `@Transactional(timeout)` | 트랜잭션 전체 시간 상한 | 추첨(10초) |

### 10-3. `ON CONFLICT DO NOTHING`과 PostgreSQL 트랜잭션 중단
PostgreSQL에서 문장이 에러를 내면 트랜잭션은 "aborted" 상태가 되어 ROLLBACK 전까지 어떤 쿼리도 못 한다. 유니크 위반을 예외로 받으면 같은 트랜잭션에서 이유를 조회할 수 없다. `ON CONFLICT DO NOTHING`은 위반을 0행으로 바꿔 트랜잭션을 살려 둔다(응모). 저장소 테스트 주석의 "제약 위반 뒤에는 트랜잭션이 중단된다"도 같은 이야기다.

### 10-4. 트랜잭션 경계 설계
- 한 트랜잭션에 묶을 것: 함께 성공하거나 함께 실패해야 하는 것(추첨 설정 + 좌석 전환, 예매 생성 + 응모 연결, 추첨 결과 전체).
- 나눌 것: 실패를 기록해야 하는 경우(배정 거절은 롤백 뒤 새 트랜잭션에서 FAILED), 하나의 실패가 다른 것을 막으면 안 되는 경우(당첨자 1명 = 1트랜잭션, 추첨 1개 = 1트랜잭션).
- 강제 도구: `Propagation.MANDATORY`(6-3).

### 10-5. 멱등성
같은 요청·메시지를 여러 번 처리해도 결과가 한 번 처리한 것과 같다. 오늘의 세 가지 방식: 멱등키 UNIQUE(응모 `request_id`), 조건부 UPDATE(결과 처리·취소), 결정론적 계산(같은 시드면 같은 순위).

### 10-6. 모듈 경계: 포트와 어댑터
lottery는 좌석·선점 기능을 인터페이스(`LotterySeatPort`, `SystemHoldPort`)로만 부르고, 구현(어댑터)은 infrastructure에 둔다. 인자는 엔티티가 아니라 ID와 값만 넘긴다. 나중에 lottery를 별도 서비스로 떼면 어댑터를 HTTP·이벤트 클라이언트로 바꾸면 되고, 인자가 그대로 API·이벤트 본문이 된다.

### 10-7. 변이 테스트(mutation testing)
코드를 일부러 틀리게 바꿔(조건 삭제·경계 변경) 테스트가 실패하는지 확인한다. 테스트가 통과한다고 테스트가 그 규칙을 지키는 것은 아니다. 오늘 각 이슈마다 핵심 조건 2~3개를 바꿔 "그 테스트만" 실패하는지 봤다. 동시성 테스트는 우연히 통과할 수 있어(8-2) 결정적으로 재현하는 테스트를 따로 둔다.

### 10-8. Flyway checksum과 테스트 DB(TS-28)
Flyway는 적용한 마이그레이션 파일의 checksum을 `flyway_schema_history`에 저장하고, 다음 기동 때 파일과 비교한다. 로컬 테스트 DB 컨테이너가 실행 사이에 살아 있으면(`compose up`만 함), 머지 전 마이그레이션의 주석 한 줄만 고쳐도 검증에서 막힌다. 해결: 테스트 DB 컨테이너만 `--force-recreate`. 이미 머지된 마이그레이션은 고치지 않고 새 버전 파일을 더한다.

### 10-9. squash 머지와 쌓은 PR, 충돌 해소
PR B를 PR A의 브랜치 위에서 만들었는데 A가 squash로 머지되면, dev에는 A의 커밋 대신 새 커밋 하나가 생긴다. 그래서 B를 dev와 병합하면 같은 내용인데도 충돌처럼 보일 수 있다. 해결: dev의 그 파일이 A와 같은 내용인지 확인한 뒤 B 쪽을 택하고 `git merge origin/dev`로 병합 커밋을 만든다. rebase·force push는 쓰지 않는다(팀 규칙). ADR 번호·판단 기록 개수처럼 여러 PR이 같은 줄을 고치는 문서도 자주 충돌한다(ADR 0040 → 0041로 재번호).

---

## 11. 질문 대비 요약

| 질문 | 한 줄 답 |
|---|---|
| 추첨이 한 번만 실행되는 근거는? | `CLOSED → DRAWN` 조건부 UPDATE가 1행일 때만 진행, 여러 서버여도 행 잠금으로 한 곳만 성공 |
| 같은 사용자가 동시에 두 번 응모하면? | `UNIQUE(lottery_id, user_id)` + `ON CONFLICT DO NOTHING`, 뒤 요청은 앞 커밋을 기다린 뒤 0행 → 409 |
| 마감 판정 시계는? | 응모는 DB `now()` 하나, 설정은 앱 시계(경합이 응모에만 있어서) |
| 배정 도중 서버가 죽으면? | 1명 = 1트랜잭션이라 그 1명만 롤백, `SKIP LOCKED` 큐에서 다음 주기가 다시 집음 |
| 결제 결과가 두 번 오면? | "연결된 WON만" 바꾸는 조건부 UPDATE라 두 번째는 0행 |
| NOT_SENT를 같은 예매로 재결제하지 않는 이유? | 결제는 예매당 1건(ADR 0026), 새 예매로 1회만 재시도(ADR 0038) |
| 승계가 좌석 수를 넘지 않는 근거는? | 추첨 행 잠금으로 계산·승격을 직렬화, 다른 워커는 건너뜀 |
| 종결 전에 좌석을 돌려주지 않는다는 근거는? | 추첨 행 잠금 뒤 새 문장으로 WON·WAITLISTED가 없음을 확인하고, DONE과 반환을 한 트랜잭션에서 한다 |
| 시스템 선점을 트랜잭션 밖에서 부르면? | `MANDATORY`라 즉시 예외, 예매만 남는 일을 막음 |
| Gateway가 입장 토큰의 회차를 어떻게 아나? | `PathPattern.matchAndExtract`로 경로 `{scheduleId}`를 꺼내 claim과 비교 |
| 낙첨에 순위를 저장하지 않는 이유? | 쓰기 수만 건을 줄여 추첨 트랜잭션을 짧게(D8). 필요하면 시드로 재계산 가능 |

---

## 12. 알려진 한계와 후속

| 항목 | 위치 |
|---|---|
| 결제 결과 소비자·만료 스케줄러 연결, 추첨 E2E, D14 안내, Gateway 공개 경로 | ISE-143 (M3) |
| 자동결제 요청 발행(outbox), 빌링키 자동결제 | ISE-92·96·89 (M2) |
| 측정 시나리오 1·2·3, 지표 대시보드 | ISE-94 (M4) |
| 좌석 전환 중 선점 겹침, 매수 한도 동시성(TS-2) | M4 동시성 트러블슈팅 |
| 추첨 시간 0.5초/4.5초 두 갈래 원인 | 미확인, 배포 환경에서 재측정 |

## 관련
- 설계: `docs/design/lottery/options.md`(D1~D14), `details.md`, `numbers-and-measurement.md`
- 결정: ADR 0034(seathold 경계), 0037(추첨석 등급), 0038(결제 실패 사유별), 0039(PG 그룹 공유), 0041(시드 BYTEA)
- 판단 기록: `docs/ai-judgments/kangmin/daily/2026-11-02.md`, `2026-11-02-lottery-must-milestone.md`
- 트러블슈팅: `docs/troubleshooting.md` TS-28
- 규칙: `docs/guides/git-workflow.md` 6절
