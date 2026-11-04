---
title: reservation-service 내부에서 좌석 선점(seathold)과 예매(reservation)를 별도 도메인 패키지로 나누는 설계의 장단점
date: 2026-10-30
updated: 2026-10-30
asker: KWON-WOO
level: 고급
tags: [modular-monolith, ddd, package-structure, bounded-context, reservation, seathold, tradeoff]
---

# reservation-service 내부에서 좌석 선점(seathold)과 예매(reservation)를 별도 도메인 패키지로 나누는 설계의 장단점

## 질문
1. `reservation-service` 안에서 좌석 선점(`seathold`)과 예매(`reservation`)를 별도 도메인 모듈(패키지)로 나눠서 설계하려고 했는데, 해당 도메인으로 나눠서 설계하는 건 좋지 않은 방법인가?
2. 두 방법(`reservation` 단일 도메인 유지 vs `seathold` 도메인 패키지 분리)의 트레이드오프를 3차례 이상 심층 검토해, 어느 쪽이 어떤 상황에서 더 합리적인지, 선택 시 이점·단점과 고려사항을 정리해 달라.

## 짧은 답
- 도메인으로 나누는 시도 자체는 트래픽 성격(오픈 정각 선점 폭주 vs 결제 후 확정·조회)과 팀원 역할(선점 담당 vs 예매 흐름 담당)을 분리한다는 점에서 합리적인 출발점이다.
- 하지만 3단계 심층 검토(① 데이터·트랜잭션 결합도, ② 역방향 상태 전이·교착 방지, ③ M4 Kafka 확장성 및 팀 협업 비용) 결과, **선점과 예매는 4개 핵심 테이블(`reservations`, `reservation_seats`, `user_schedule_quotas`, `schedule_seats`)의 정방향·역방향 상태 전이를 공유하는 단일 생명주기**다.
- 따라서 **현시점(M2~M3)에서 가장 합리적인 선택은 [방법 1: `reservation` 단일 도메인 패키지 유지 + 하위 기능(또는 클래스 접두어) 분리]**다. 만약 M4 접수형 고도화와 향후 MSA 물리 분리 가능성까지 염두에 두고 **[방법 2: `seathold` 패키지 분리]**를 선택한다면, 반드시 **`seathold → reservation` 단방향 의존 규칙**과 **공통 해제(Release) 포트**를 먼저 세워 순환 참조와 잠금 순서 불일치를 막아야 한다.

---

## 자세히: 3단계 심층 검토 (3-Pass Review)

### 1차 검토: 데이터 소유권과 로컬 트랜잭션 경계 (Data Ownership & Transaction Boundary)

모듈러 모놀리스에서 패키지(모듈)를 나눌 때 첫 번째 질문은 **"각 모듈이 자기 테이블만 단독으로 읽고 쓸 수 있는가?"**다.

| 테이블 | 선점(`seathold`, 최권우)의 접근 | 예매(`reservation`, 반효승)의 접근 | 추첨(`lottery`, 이강민)의 접근 |
|---|---|---|---|
| `seat_hold_requests` | INSERT(`REQUESTED`/`HELD`), UPDATE(`HELD`/`REJECTED`), SELECT(멱등·폴링) | 접근 없음 | 접근 없음 |
| `user_schedule_quotas` | 선점 시 `+n` UPSERT/UPDATE, 만료 시 `-n` UPDATE, 대사 SELECT/UPDATE | 결제 거절(`PaymentDeclined`)·취소(`cancel`) 시 `-n` UPDATE | 당첨 배정 시 `+n` UPDATE (SA 12-3) |
| `schedule_seats` | 선점 시 `AVAILABLE → HELD`, 만료 시 `HELD → AVAILABLE`, 대사 | 승인 시 `HELD → SOLD`, 거절·취소 시 `AVAILABLE` | 당첨 배정 시 `HELD` |
| `reservations` | 선점 시 `HELD` INSERT, 만료 시 `HELD → EXPIRED` UPDATE | 단건/목록 조회, 승인 시 `HELD → CONFIRMED`, 거절 시 `HELD → RELEASED`, 취소 시 `CONFIRMED → CANCELED` | 당첨 시 `HELD(LOTTERY)` INSERT |
| `reservation_seats` | 선점 시 `HELD` INSERT, 만료 시 `HELD → RELEASED` UPDATE, 대사 | 승인 시 `HELD → CONFIRMED`, 거절·취소 시 `RELEASED` | 당첨 시 `HELD` INSERT |
| `reservation_status_histories` | `null → HELD`, `HELD → EXPIRED` INSERT | `HELD → CONFIRMED`, `HELD → RELEASED`, `CONFIRMED → CANCELED` INSERT | `null → HELD` INSERT |

- **검토 결과**:
  - 진정으로 `seathold`만 단독 소유하는 테이블은 **`seat_hold_requests` 단 1개**뿐이다.
  - 나머지 5개 테이블(`user_schedule_quotas`, `schedule_seats`, `reservations`, `reservation_seats`, `reservation_status_histories`)은 선점·예매·추첨이 모두 건드린다.
  - 특히 동기형 선점(`POST /api/v1/schedules/{scheduleId}/reservations`) 한 번에 위 6개 테이블이 **단일 DB 트랜잭션**으로 묶이고, 응답 직전에 `reservation`의 **`PaymentTokenIssuer`**까지 호출해야 한다.
  - 따라서 패키지를 둘로 나누더라도 DB 수준에서는 격리되지 않으며, 두 모듈 사이에 최소 4~5개의 도메인 포트/엔티티 참조가 발생할 수밖에 없다.

---

### 2차 검토: 역방향 상태 전이(만료·거절·취소)와 교착(Deadlock) 방지 규칙 (SA 8-4)

두 번째로, 선점 이후 상태가 풀리는 **역방향 전이(Release / Expire / Cancel)**와 **잠금 순서(SA 8-4)**를 코드로 구현할 때 어떤 일이 벌어지는지 시뮬레이션했다.

- **SA 8-4의 철칙**: 모든 트랜잭션은 데드락을 막기 위해 반드시 **`user_schedule_quotas` → `schedule_seats`(ID 오름차순) → `reservations`** 순서로 잠가야 한다.
- **경합하는 3개의 흐름**:
  1. **선점 만료 스케줄러(`ISE-13`, 최권우)**: 5분 지난 `HELD` 예매를 찾아 `user_schedule_quotas(-n)` → `schedule_seats(AVAILABLE)` → `reservations(EXPIRED)` + `reservation_seats(RELEASED)` + `histories` 기록.
  2. **결제 결과 소비자(`PaymentDeclined` / 만료 후 `PaymentApproved`, 반효승, ADR 0027)**: `HELD` 예매를 `user_schedule_quotas(-n)` → `schedule_seats(AVAILABLE)` → `reservations(RELEASED 또는 EXPIRED)` + `reservation_seats(RELEASED)` + `histories` 기록.
  3. **예매 취소 API(`cancel`, 반효승)**: `CONFIRMED` 예매를 `user_schedule_quotas(-n)` → `schedule_seats(AVAILABLE)` → `reservations(CANCELED)` + `reservation_seats(RELEASED)` + `tickets(VOID)` + `histories` 기록.

- **검토 결과**:
  - 위 1·2·3번 흐름은 **잠금 순서와 4개 테이블 갱신 쿼리가 90% 동일**하다.
  - 만약 **방법 2(`seathold`와 `reservation` 분리)**를 택하면서 만료 스케줄러(`seathold`)와 결제 거절 소비자(`reservation`)가 각자 자기 패키지의 Repository를 조합해 이 로직을 따로 짜면:
    - 한쪽에서 조건부 UPDATE가 0행일 때 앞선 쿼터 차감을 롤백하지 않는 버그(이중 차감)가 생기기 쉽다.
    - `UserScheduleQuota`를 `seathold`에 두면 `reservation`(결제 거절·취소)이 `seathold`를 호출해야 하고, `Reservation`을 `reservation`에 두면 `seathold`(선점·만료)가 `reservation`을 호출해야 하므로 **`seathold ↔ reservation` 양방향 순환 의존**이 즉시 발생한다.
  - 이를 막으려면 "예매 생성(Hold)"과 "예매 해제(Release/Expire)"의 원자적 상태 전이 메서드를 한 도메인 계층(`reservation.domain`)이 독점 제공하고, `seathold`는 이를 호출만 해야 한다.

---

### 3차 검토: M4 고도화(Kafka 접수형 전환), AI 검증 #7(4계층), 팀 협업·Git 충돌

세 번째로, 앞으로 진행할 M4 이슈(`ISE-15`~`ISE-18`)와 필수 검증 과제(AI 설계 검증 #7), 그리고 반효승 님과의 동시 작업 관점에서 검토했다.

1. **M4 Kafka 접수형(`ISE-15`~`ISE-17`) 추가 시 클래스 증가량**:
   - 선점 쪽에 추가될 클래스: `SeatHoldController`, `SeatHoldRequestDto`, `SeatHoldResponseDto`, `SeatHoldService`(동기/접수 분기), `SeatHoldConsumer`(`seat-hold.request` 구독), `SeatHoldDlqHandler`, `SeatHoldTimeoutScheduler`(30초 `REQUESTED → REJECTED`), `SeatHoldExpirationScheduler`(5분 `HELD → EXPIRED`), `SeatHoldReconciliationService`(`ISE-14` 대사). 최소 **12~15개 클래스**다.
   - 예매 쪽에 추가될 클래스(반효승): `ReservationQueryController`, `ReservationCancelController`, `ReservationQueryService`, `ReservationCancelService`, `PaymentResultConsumer`, `PaymentResultService`, `PaymentTokenIssuer`, `InternalReconciliationController`(ADR 0029), `Ticket` 엔티티 등 **12~15개 클래스**다.
   - 합치면 `reservation` 모듈 하나에 **약 30~35개 클래스**가 들어간다.
2. **AI 설계 검증 #7 (SA 14장 필수 항목) 관점**:
   - 프롬프트 4번 질문이 *"ReservationService가 너무 많은 책임을 가진 Fat Service가 아닌지"*다.
   - 패키지 구조와 무관하게 **`ReservationService` 하나에 선점·조회·결제반영·만료를 다 넣으면 Fat Service 판정**을 받는다. 반드시 응용 서비스를 `SeatHoldService`, `SeatHoldExpirationService`, `ReservationQueryService`, `PaymentResultService` 등으로 쪼개야 한다.
3. **협업 및 Git 충돌 관점**:
   - 두 사람이 같은 폴더에서 작업할 때 충돌이 나는 지점은 **공유 파일(`Reservation.java`, `ReservationRepository.java`, `ScheduleSeatRepository.java`)을 동시에 수정할 때**지, 같은 폴더에 서로 다른 파일(`SeatHoldService.java` vs `ReservationQueryService.java`)을 만들 때가 아니다.

---

## 두 방법의 트레이드오프 종합 비교

| 비교 축 | 방법 1: `reservation` 단일 도메인 패키지 유지<br>(내부에서 클래스/하위 패키지 분리) | 방법 2: `seathold`와 `reservation`을 형제 도메인 패키지로 분리<br>(`reservation.seathold.*` vs `reservation.reservation.*`) |
|---|---|---|
| **패키지 모양** | `com.iseonjwa.reservation.reservation`<br>├─ `presentation` (`SeatHoldController`, `ReservationController`)<br>├─ `application` (`SeatHoldService`, `ReservationExpirationService`, ...)<br>├─ `domain` (`Reservation`, `SeatHoldRequest`, `UserScheduleQuota`, ...)<br>└─ `infrastructure` | `com.iseonjwa.reservation.seathold` (`presentation`, `application`, `domain`, `infrastructure`)<br>`com.iseonjwa.reservation.reservation` (`presentation`, `application`, `domain`, `infrastructure`) |
| **핵심 이점 (Pros)** | 1. **순환 참조 원천 차단**: `Reservation`, `UserScheduleQuota`, `SeatHoldRequest`가 한 `domain`에 있어 선점·만료·거절·취소가 같은 잠금 순서(SA 8-4)와 상태 전이 컴포넌트를 자연스럽게 공유함<br>2. **SA 13장 정본과 100% 일치**: 문서 수정·ADR 추가·팀 설득 비용이 0임<br>3. **현재 로컬 `ISE-10` 커밋과 일치**: 이미 커밋된 10개 엔티티의 패키지를 옮길 필요가 없음 | 1. **접수형(M4) 복잡도 격리**: `SeatHoldRequest`, `SeatHoldConsumer`, Outbox 접수, 30초 타임아웃 등 '선점 입구 큐잉' 코드가 `reservation` 원장 코드와 물리적으로 분리됨<br>2. **담당자별 디렉터리 분리**: 권우(`seathold`)와 효승(`reservation`)의 주 작업 폴더가 나뉘어 탐색이 직관적임<br>3. **향후 MSA 분리 대비**: 접수형 입구만 별도 서버로 떼어낼 때 경계가 보임 |
| **핵심 단점 (Cons)** | 1. **패키지 내 클래스 수 증가**: M4까지 가면 한 패키지의 계층별로 클래스가 7~10개씩 모여 파일 목록이 길어짐 (단, 하위 패키지나 `SeatHold*` 접두어로 완화 가능)<br>2. **모듈 간 경계 강제력 약화**: 같은 패키지라 누가 어떤 엔티티를 건드리는지 패키지 import만으로는 보이지 않음 | 1. **양방향 의존(순환 참조) 함정**: `UserScheduleQuota`를 `seathold`에 두고 `Reservation`을 `reservation`에 두면, 선점 시 `seathold → reservation`, 결제 거절·취소 시 `reservation → seathold` 참조가 생김<br>2. **잠금 순서·상태 전이 파편화 위험**: 만료(`seathold`)와 결제 거절(`reservation`)이 같은 테이블을 따로 UPDATE하다가 정합성 버그가 나기 쉬움<br>3. **합의 및 문서 비용**: SA 13장 수정 및 효승 님과의 포트 설계 합의 필요 |

---

## 어느 쪽이 어떤 상황에서 더 합리적인가?

### 1. [방법 1]이 더 합리적인 상황 (현재 프로젝트 조건에 부합)
- **선점과 예매 확정이 같은 DB의 같은 테이블(`reservations`, `user_schedule_quotas`)을 한 트랜잭션으로 묶어 처리하는 경우** (우리의 M2 동기형 및 M4 접수형 Consumer 모두 해당).
- 마감 일정이 촉박하고(M2 진행 중), 이미 팀원과 맞춘 SA 13장 구조 및 PR #96(`global.vo.Money`) 위에서 빠르게 `ISE-10` → `ISE-11` → `ISE-12`를 돌파해야 하는 경우.
- **절충 팁 (방법 1-Plus)**: `reservation` 도메인 패키지 안에서 `domain/entity`와 `domain/repository`는 하나로 공유하되, `application`과 `presentation`만 **`SeatHold*` 접두어**(또는 `application/seathold` 하위 패키지)로 모아 두면, 방법 1의 정합성 이점과 방법 2의 가독성 이점을 동시에 얻을 수 있다.

### 2. [방법 2]가 더 합리적인 상황
- 선점(`seathold`)이 자체 저장소(예: Redis 선점 또는 `seat_hold_requests`만 쓰는 순수 접수 계층)만 다루고, `Reservation` 원장 생성은 이벤트나 명확한 단방향 Command 포트로 넘기는 구조일 때.
- 또는 팀원 두 명이 아예 패키지 루트부터 폴더를 갈라 작업하기로 합의하고, 공유 테이블의 소유권 규칙을 사전에 엄격히 정의할 여유가 있을 때.

---

## 각 방법을 선택했을 때 반드시 챙겨야 할 고려사항 (Checklist)

### 만약 [방법 1: `reservation` 단일 도메인 유지]를 선택한다면
1. **Fat Service 방지 (AI 검증 #7 대비)**:
   - 절대 `ReservationService` 하나에 모든 메서드를 넣지 않는다.
   - `SeatHoldService`(선점 트랜잭션·멱등), `SeatHoldExpirationService`(만료 스케줄러), `SeatHoldReconciliationService`(대사), `ReservationQueryService`(조회), `PaymentResultService`(결제 이벤트 반영)로 응용 서비스를 책임별로 분리한다.
2. **공유 Repository 충돌 방지**:
   - `ISE-10` PR에서 기본 Repository(`ReservationRepository`, `ReservationSeatRepository`, `UserScheduleQuotaRepository`, `SeatHoldRequestRepository`)를 먼저 만들어 머지해 두어야, 이후 권우 님(`ISE-11`)과 효승 님(`ISE-62`)이 각자 필요한 쿼리 메서드만 추가하며 충돌을 줄일 수 있다.
3. **워킹 트리 정리**:
   - 로컬에 untracked로 남아 있는 빈 `seathold/`, `shared/` 폴더를 지우고, 중복된 `reservation.domain.vo.Money`를 삭제해 PR #96의 `global.vo.Money`로 연결한다.

### 만약 [방법 2: `seathold` 도메인 패키지 분리]를 선택한다면
1. **순환 참조(`seathold ↔ reservation`) 차단 규칙 (가장 중요)**:
   - **엔티티 배치**:
     - `seathold.domain`: **`SeatHoldRequest`, `SeatHoldRequestStatus`만** 둔다.
     - `reservation.domain`: **`Reservation`, `ReservationSeat`, `ReservationStatusHistory`, `UserScheduleQuota`, `Ticket`**을 모두 여기에 둔다. (`UserScheduleQuota`는 결제 거절·취소·추첨에서도 차감/증가하므로 예매 원장 쪽에 두어야 `reservation → seathold` 역방향 의존이 생기지 않는다.)
   - **의존 방향**: 오직 **`seathold` → `reservation`, `concert`** 단방향만 허용한다. `reservation` 패키지는 `seathold` 패키지의 존재를 절대 import하지 않는다.
2. **상태 전이(Hold / Release)의 단일 진입점**:
   - 만료 스케줄러(`seathold`)와 결제 거절 소비자(`reservation`)가 각자 SQL을 짜지 않도록, `reservation` 도메인/인프라가 제공하는 공통 해제 메서드(잠금 순서 `quota → schedule_seat → reservation` 보장)를 함께 호출한다.
3. **팀 합의 및 문서 반영**:
   - 반효승 님에게 "`SeatHoldRequest`와 선점 API/컨슈머/스케줄러는 `seathold` 패키지에 두고, `Reservation`·`UserScheduleQuota`는 `reservation` 패키지에 두어 `seathold → reservation` 단방향으로 호출하겠다"고 공유하고 SA 13장 패키지 트리에 한 줄을 추가한다.

## 면접 포인트
- **Q. 처음에 `seathold`와 `reservation`을 별도 도메인 모듈로 나누려다가 구조를 고민한 이유는 무엇인가요?**
  - "처음에는 트래픽이 집중되는 '좌석 선점'과 이후의 '예매 관리'를 별도 도메인 패키지로 분리하려 했습니다. 하지만 데이터 소유권과 상태 전이 경로를 3차례에 걸쳐 검증해 보니, 선점 만료(`seathold`)와 결제 거절·취소(`reservation`)가 `user_schedule_quotas`, `schedule_seats`, `reservations` 세 테이블을 동일한 비관적 락 순서(SA 8-4)로 역전이시켜야 했습니다. 두 도메인이 같은 테이블을 각자 수정하면 데드락 방지 순서가 깨지거나 모듈 간 양방향 순환 참조가 발생하기 때문에, 원장 엔티티(`Reservation`, `UserScheduleQuota`)와 상태 전이 규칙은 `reservation` 도메인에 응집시키고, 선점 유스케이스(`SeatHoldService`)와 접수 요청(`SeatHoldRequest`)만 단방향으로 분리해 정합성과 모듈 응집도를 모두 지켰습니다."

## 참고
- [`docs/core/SA MASTER.md`](../../../core/SA%20MASTER.md) 7-1절, 8-3절, 8-4절, 12-3절, 13장, 14장(#7)
- [`docs/decisions/0027-reservation-payment-event-contract.md`](../../../decisions/0027-reservation-payment-event-contract.md)
- [`docs/sa-review/06-layered-architecture-check.md`](../../../sa-review/06-layered-architecture-check.md)
