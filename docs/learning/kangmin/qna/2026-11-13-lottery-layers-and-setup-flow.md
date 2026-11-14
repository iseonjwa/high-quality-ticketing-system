---
title: lottery 모듈은 계층과 인터페이스를 어떻게 나눴고, 추첨 설정 요청은 코드에서 어떻게 흐르나
date: 2026-11-13
updated: 2026-11-13
asker: kangmin
level: 중급
tags: [ddd, layered-architecture, port-adapter, repository, lottery, jpa, locking]
---

# lottery 모듈은 계층과 인터페이스를 어떻게 나눴고, 추첨 설정 요청은 코드에서 어떻게 흐르나

## 질문
담당한 lottery를 코드 수준에서 다시 이해하려 한다. DDD 계층 구조와 인터페이스 형태가 익숙하지 않으니, 계층과 인터페이스를 먼저 설명하고 첫 단계부터 하나씩 뜯어 달라.

## 짧은 답
- 패키지는 `presentation → application → domain ← infrastructure` 방향으로만 의존한다. infrastructure가 domain·application의 인터페이스를 구현하는 쪽이라 화살표가 안쪽으로 향한다(의존성 역전 원칙, DIP).
- 인터페이스는 세 종류다. (1) domain의 저장소 인터페이스 `LotteryRepository`: 추첨이 소유한 테이블. (2) application의 포트 `LotterySeatPort`·`SystemHoldPort`: 다른 모듈(concert·seat)의 좌석을 바꾸는 통로. (3) infrastructure 안의 Spring Data 인터페이스 `SpringDataLotteryRepository`: 실제 SQL, package-private.
- 추첨 설정은 `요청 DTO → Command → LotterySettings(값 객체) → Lottery.create(정적 팩토리) → 저장 + 좌석 전환(포트) → LotteryResult → 응답 DTO`로 흐른다. 회차 행 잠금(`FOR UPDATE`) 하나로 같은 회차의 설정·수정을 줄 세우고, 좌석이 모자라면 예외로 추첨 행까지 함께 롤백한다.

## 계층과 의존 방향
| 패키지 | 들어 있는 것 | 의존해도 되는 것 |
|---|---|---|
| `presentation` | 컨트롤러, 요청·응답 DTO, 스케줄러 | application |
| `application` | 유스케이스 서비스(트랜잭션 경계), Command·Result, 포트 인터페이스 | domain (회차 읽기는 모듈 규칙상 concert domain 직접) |
| `domain` | 엔티티, 상태 enum, 정책(`policy`), 에러 코드, 저장소 인터페이스 | 없음(JPA 애너테이션만 예외) |
| `infrastructure` | 저장소 구현, Spring Data, 포트 어댑터 | domain·application의 인터페이스 |

스케줄러가 presentation에 있는 이유: HTTP 요청처럼 "바깥에서 유스케이스를 깨우는 입구"라서다.

## 인터페이스 세 종류
| 종류 | 선언 위치 | 구현 | 왜 이 자리인가 |
|---|---|---|---|
| 저장소 `LotteryRepository` | domain/repository | `JpaLotteryRepository` | 추첨이 소유한 집합을 저장·조회하는 계약이라 도메인 언어로 둔다 |
| 포트 `LotterySeatPort` | application | `ScheduleSeatLotteryAdapter` | 좌석은 concert 모듈 소유. 서비스를 나누면 구현만 내부 API 클라이언트로 바꾼다 |
| `SpringDataLotteryRepository` | infrastructure (package-private) | Spring이 런타임에 프록시 생성 | `JpaRepository`의 수십 개 메서드와 SQL을 바깥에 노출하지 않는다 |

`JpaLotteryRepository`는 대부분 위임만 하는 얇은 어댑터다. 그래도 두는 이유는 (1) 도메인이 Spring Data에 묶이지 않고 (2) 반환형 변환(`int == 1 → boolean`, `List → Optional`)과 잠금 대기 상한 설정 같은 기술 세부를 한곳에 모으기 때문이다.

## 추첨 설정 흐름 (POST /api/v1/admin/schedules/{scheduleId}/lotteries)
1. `AdminLotteryController`: `@RequireRole(ADMIN)`, `@Valid`로 형식 검증, `request.toCommand(scheduleId)`.
2. `LotteryCreateRequest`: `@NotNull`·`@Positive`·`@Min/@Max` 같은 단일 값 형식만 본다.
3. `LotteryAdminService.create` (`@Transactional`):
   - `scheduleRepository.findByIdForUpdate` → `PESSIMISTIC_WRITE`로 회차 행 잠금.
   - `existsByScheduleId`로 중복 확인(지운 추첨 포함, UNIQUE 제약과 같은 기준).
   - `Lottery.create(...)`: `settings.validate`(값 관계), `requireBeforeSetupDeadline`(시각), 시드 생성, `OPEN`.
   - `convert(...)` → `LotterySeatPort.convertToLottery` → `UPDATE ... WHERE id = ANY(ARRAY(SELECT ... LIMIT n FOR UPDATE))`.
   - 바꾼 수 < 요청 → `LOTTERY_SEATS_NOT_ENOUGH`(409) 예외 → 트랜잭션 롤백으로 이미 바꾼 좌석도 원래대로.
   - `save` 후 `LotteryResult.of`: 추첨 시각이 권장 시간대 밖이면 경고만 붙인다.
4. `LotteryResponse.from(result)`: 엔티티를 응답으로 바로 내보내지 않는다.

## 검증 분담
| 어디서 | 무엇을 | 예 |
|---|---|---|
| 요청 DTO | 값 하나의 형식 | 필수, 양수, 1~2매 |
| 엔티티(`LotterySettings.validate`, `requireBeforeSetupDeadline`) | 값끼리의 관계·시각 규칙 | 응모 시작 < 마감 ≤ 추첨, 추첨+60분 ≤ 공연 시작, LOTTERY 등급 금지 |
| 응용 서비스 | 다른 데이터와의 관계 | 회차 존재, 중복 설정, 좌석 부족 |
| DB | 최후 방어 | UNIQUE(schedule_id) |

엔티티에서 관계 규칙을 보는 이유: API 말고 다른 경로(테스트, 나중의 배치)로 만들어도 같은 규칙을 지키게 하려는 것이다.

## 객체가 바뀌는 사슬과 이유
| 객체 | 계층 | 왜 따로 두나 |
|---|---|---|
| `LotteryCreateRequest` | presentation | JSON 모양과 검증 애너테이션은 웹 관심사 |
| `CreateLotteryCommand` | application | 유스케이스 입력. 웹이 아닌 입구(스케줄러·테스트)도 같은 입력을 쓴다 |
| `LotterySettings` (record) | domain | 생성·수정이 같은 규칙으로 검사할 값 묶음 |
| `Lottery` | domain | 상태와 규칙을 가진 엔티티 |
| `LotteryResult` | application | 엔티티를 트랜잭션 밖으로 내보내지 않고 경고 같은 유스케이스 결과를 더함 |
| `LotteryResponse` | presentation | 응답 형식(enum → 문자열) |

## 동시성 포인트
- 회차 행 `FOR UPDATE`: "중복 확인 → INSERT" 사이에 다른 설정이 끼어드는 확인 후 행동(check-then-act) 경쟁을 막는다.
- 수정(`update`)은 `findScheduleIdById`로 값만 먼저 읽고 회차를 잠근 뒤 `findById`로 엔티티를 읽는다. 엔티티를 먼저 영속성 컨텍스트에 올리면 잠금 뒤에도 잠금 전 값을 쓰게 된다.
- 좌석 전환 SQL이 `IN` 대신 `ANY(ARRAY(...))`인 이유: `IN (SELECT ... LIMIT n FOR UPDATE)`는 실행 계획에 따라 서브쿼리가 다시 실행돼 LIMIT보다 많이 바꿀 수 있다. `ARRAY(...)`는 한 번만 실행된다.
- 내부 `FOR UPDATE`와 `status = 'AVAILABLE'`: 선점(HELD)과 엇갈려도 이미 잡힌 좌석은 바꾸지 않는다.

## 확인할 질문
- `LotteryRepository`는 domain에, `LotterySeatPort`는 application에 있다. 위치를 바꾸면 무엇이 어색해지나?
- `update`에서 `findScheduleIdById` 없이 `findById` → `findByIdForUpdate` 순서로 쓰면 어떤 실패가 생기나?
- `converted < count`와 `converted != count`를 따로 보는 이유는?
- `Lottery.getSeed()`가 `clone()`을 돌려주지 않으면 무엇이 깨질 수 있나?

## 근거 파일
- `reservation-service/src/main/java/com/iseonjwa/reservation/lottery/` 아래 `*/package-info.java`, `domain/entity/Lottery.java`, `domain/repository/LotteryRepository.java`, `application/LotteryAdminService.java`, `application/LotterySeatPort.java`, `infrastructure/persistence/JpaLotteryRepository.java`, `infrastructure/persistence/SpringDataLotteryRepository.java`, `infrastructure/seat/ScheduleSeatLotteryAdapter.java`, `presentation/AdminLotteryController.java`
- `reservation-service/src/main/java/com/iseonjwa/reservation/concert/infrastructure/persistence/SpringDataScheduleSeatRepository.java` (`updateGradeToLottery`)
