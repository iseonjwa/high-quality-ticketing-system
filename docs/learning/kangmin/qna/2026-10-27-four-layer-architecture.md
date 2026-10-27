---
title: 이 프로젝트의 4계층(표현·응용·도메인·인프라)은 각각 무엇을 맡고 어떻게 의존하나
date: 2026-10-27
updated: 2026-10-27
asker: GGangMY
level: 중급
tags: [layered-architecture, dip, jpa, kafka, package-structure]
---

# 이 프로젝트의 4계층(표현·응용·도메인·인프라)은 각각 무엇을 맡고 어떻게 의존하나

## 질문
4계층 상세 설명. 결정 기록 #22(10/26 결정)와 SA 13장의 4계층을 서비스 뼈대(`presentation/application/domain/infrastructure/global`)에 실제로 어떻게 채우는지 알고 싶다.

## 짧은 답
- **presentation**: HTTP를 받고 돌려준다. **application**: 유스케이스 하나를 트랜잭션 하나로 묶어 순서를 정한다. **domain**: 규칙과 상태를 가진다. **infrastructure**: DB·Redis·Kafka·PG 같은 바깥 기술을 다룬다.
- 의존은 바깥에서 안쪽으로만 간다: presentation → application → domain ← infrastructure.
- 핵심은 마지막 화살표다. infrastructure가 domain을 부르는 게 아니라, **domain이 정한 인터페이스(저장소)를 infrastructure가 구현**한다(의존 역전, DIP). 그래서 domain은 아무것도 모른다.
- 우리 팀의 타협: 엔티티에 JPA 애너테이션은 허용한다(도메인 모델과 JPA 엔티티를 따로 두지 않는다). Kafka 코드는 domain·application에 절대 넣지 않는다.

## 자세히

### 1. 의존 방향

```
 HTTP 요청                                Kafka 메시지
    │                                          │
    ▼                                          ▼
┌──────────────┐                     ┌────────────────────┐
│ presentation │                     │ infrastructure     │
│ Controller   │                     │ (Consumer)         │
└──────┬───────┘                     └─────────┬──────────┘
       │ 호출                                  │ 호출
       ▼                                       ▼
┌────────────────────────────────────────────────────────┐
│ application  유스케이스 · @Transactional                │
└──────────────────────────┬─────────────────────────────┘
                           │ 사용
                           ▼
┌────────────────────────────────────────────────────────┐
│ domain  엔티티 · 규칙 · 저장소 "인터페이스"              │
└──────────────────────────▲─────────────────────────────┘
                           │ 구현 (implements)
┌──────────────────────────┴─────────────────────────────┐
│ infrastructure  JPA 구현 · Redis · Kafka Producer · PG  │
└────────────────────────────────────────────────────────┘
```

- 위 계층은 아래 계층을 알지만, 아래 계층은 위 계층을 모른다.
- domain은 화살표가 들어오기만 하고 나가지 않는다. DB를 PostgreSQL에서 다른 것으로 바꾸거나 Redis를 빼도 domain 코드는 그대로다.
- Kafka Consumer는 메시지를 받는 입구라 presentation과 같은 역할이지만, 기술 코드라서 SA는 infrastructure에 둔다. 받은 뒤에는 application을 부른다.

### 2. 계층별 책임

| 계층 | 담는 것 | 하지 않는 것 | 이 프로젝트 예시 |
|---|---|---|---|
| presentation | `@RestController`, 요청·응답 DTO, `@Valid` 형식 검증, 헤더(`X-User-Id`) 읽기 | 비즈니스 판단, 트랜잭션, 엔티티를 그대로 응답 | `ReservationController`, `HoldSeatsRequest`(seatIds 1~4개, 비어 있지 않음) |
| application | 유스케이스 메서드, `@Transactional` 경계, 저장소 호출 순서, 다른 모듈 호출, 이벤트 저장 요청 | 규칙 계산(엔티티에 위임), SQL·Redis 명령, HTTP 형식 | `ReservationService.hold()`: 한도 → 좌석 → 예매 저장을 한 트랜잭션으로 |
| domain | 엔티티, 값 객체, 상태 전이 규칙, 금액·한도 계산, 저장소 인터페이스, 도메인 예외 | 프레임워크 호출(JPA 애너테이션만 예외), DTO, Kafka | `Reservation.confirm()`(HELD일 때만 CONFIRMED), `ReservationRepository` 인터페이스 |
| infrastructure | 저장소 구현(Spring Data JPA, 네이티브 쿼리), Redis Lua, Kafka Producer·Consumer, Outbox 릴레이, PG 클라이언트 | 규칙 판단(넘겨받은 결과만 저장·전송) | 좌석 조건부 UPDATE 쿼리, 대기열 seq 발급 Lua, `PaymentResultConsumer` |
| global | 서비스 공통 설정과 Gateway 헤더 해석 | 업무 로직 | `CurrentUser` 해석기, `JpaConfig` |

### 3. 요청 하나가 계층을 지나가는 모습 (좌석 선점)

`POST /api/v1/reservations` (SA 8-1, 12-7). 아래 코드는 위치와 역할을 보이기 위한 예시이며 실제 구현이 아니다.

```java
// presentation: 형식 검증과 변환만
@PostMapping("/api/v1/reservations")
ResponseEntity<...> hold(@RequestHeader("X-User-Id") Long userId,
                         @Valid @RequestBody HoldSeatsRequest request) {
    HoldResult result = reservationService.hold(request.toCommand(userId));
    return ...(HoldSeatsResponse.from(result));   // 엔티티가 아니라 응답 DTO
}

// application: 순서와 트랜잭션
@Transactional
public HoldResult hold(HoldCommand command) {
    quotaRepository.reserve(command.userId(), command.scheduleId(), command.count());   // 0행이면 예외
    seatRepository.holdAll(command.scheduleId(), command.seatIds());                    // 바뀐 행 수 ≠ n이면 예외
    Reservation reservation = Reservation.hold(command.userId(), command.seatIds(), clock); // 규칙은 도메인이
    reservationRepository.save(reservation);
    return HoldResult.of(reservation, paymentTokenIssuer.issue(reservation));
}

// domain: 규칙과 인터페이스
public class Reservation {
    public static Reservation hold(...) { /* 매수 1~4, 만료 = 지금 + 5분 */ }
    public void confirm() { if (status != HELD) throw new InvalidReservationState(...); status = CONFIRMED; }
}
public interface SeatRepository { void holdAll(long scheduleId, List<Long> seatIds); }

// infrastructure: 도메인 인터페이스를 SQL로 구현
@Repository
class SeatRepositoryImpl implements SeatRepository {
    public void holdAll(...) { int updated = jpa.holdAvailable(...); if (updated != seatIds.size()) throw new SeatAlreadyHeld(); }
}
```

여기서 볼 점:
- 동시성 방어(조건부 UPDATE, 부분 유니크)는 SQL에 있으니 infrastructure다. "0행이면 실패"라는 의미는 도메인 예외로 바꿔 올린다.
- 트랜잭션은 application에만 건다. 컨트롤러나 저장소 구현에 `@Transactional`을 흩어 두면 "어디까지가 한 단위인지"가 안 보인다.
- 토큰 발급(`paymentTokenIssuer`)도 서명 키를 쓰는 기술이라 인터페이스는 application 또는 domain에, HMAC 구현은 infrastructure에 둔다.

### 4. 저장소 인터페이스를 두는 두 가지 방법

| 방법 | 모양 | 장점 | 단점 |
|---|---|---|---|
| A. 도메인 인터페이스 + 구현체 (SA 13장) | domain: `SeatRepository`, infrastructure: `SeatJpaRepository extends JpaRepository` + `SeatRepositoryImpl implements SeatRepository` | domain이 Spring Data를 모른다. 테스트에서 가짜 저장소로 바꾸기 쉽다 | 파일이 2~3개로 늘고 위임 코드가 생긴다 |
| B. 도메인에 `JpaRepository` 상속 인터페이스 | domain: `SeatRepository extends JpaRepository<Seat, Long>` | 짧다. Spring 예제가 대부분 이렇다 | domain이 Spring Data에 묶인다. "domain은 의존 없음" 규칙과 어긋난다 |

SA는 "domain: 저장소 인터페이스 / infrastructure: JPA 저장소 구현"이라 **A**가 기준이다. 단순 CRUD만 있는 모듈에서 B를 허용할지는 팀이 정할 일이다(확인할 질문).

### 5. 다른 구조와 비교 (#22에서 4계층을 고른 이유)

| 구조 | 생김새 | 장점 | 대가 |
|---|---|---|---|
| 3계층 (Controller·Service·Repository) | 기술 기준 3칸 | 가장 익숙하고 빠르다 | 규칙이 Service에 몰려 비대해진다(트랜잭션 스크립트). Service가 Redis·Kafka를 직접 부르기 쉽다 |
| **4계층 + DIP (선택)** | 3계층에서 domain을 떼고 인프라를 바깥으로 | 규칙이 엔티티에 모이고, 기술 교체가 infrastructure 안에서 끝난다. 팀원이 익숙한 구조에서 크게 벗어나지 않는다 | 인터페이스·DTO 변환 코드가 늘어난다 |
| 헥사고날 (Ports & Adapters) | 포트(in/out) + 어댑터 | 경계가 가장 명확하다 | 패키지와 인터페이스가 많아 5주 일정에 학습 비용이 크다 |
| 클린 아키텍처 | 엔티티·유스케이스·어댑터·프레임워크 | 순수한 도메인 | JPA 엔티티와 도메인 모델을 나누면 매핑 코드가 2배다 |

4계층 + DIP는 헥사고날의 핵심(도메인이 바깥을 모름)만 가져오고 포트·어댑터 이름과 모델 분리는 뺀 절충이다.

감수한 점:
- 엔티티에 `@Entity`, `@Column`을 쓰므로 도메인이 JPA에 완전히 자유롭지는 않다. 대신 매핑 코드가 없다.
- 파일 수가 늘고, 처음에는 "이건 어느 계층?" 판단에 시간이 든다(아래 표로 줄인다).

### 6. "이건 어디에?" 판단표

| 코드 | 위치 | 이유 |
|---|---|---|
| 요청 JSON 필드 검증(`@NotNull`, `@Size(max=4)`) | presentation DTO | 형식 검증은 입구에서 |
| "회차당 4매 넘으면 안 됨" | domain(규칙) + infrastructure(조건부 UPDATE로 강제) | 규칙의 정의는 도메인, 동시 요청 방어는 DB |
| `@Transactional` | application | 유스케이스 하나 = 트랜잭션 하나 |
| 상태 전이 HELD → CONFIRMED | domain 엔티티 메서드 | 상태 규칙이 한 곳에 모인다 |
| 대기열 seq 발급 Lua | infrastructure | Redis 명령은 기술 |
| 대기열 "seq ≤ admitted면 입장" 판정 | domain | 입장 규칙 자체 |
| Kafka 이벤트 발행 | application은 Outbox 저장만 요청 → infrastructure 릴레이가 전송 | 트랜잭션과 발행을 묶기 위해(Outbox) |
| Kafka 수신 | infrastructure Consumer → application 호출 | Kafka 코드는 domain·application 금지 |
| PG 호출 | infrastructure 클라이언트, 인터페이스는 domain/application | Mock PG를 실제 PG로 바꿔도 위는 그대로 |
| 응답 DTO 변환 | presentation(`Response.from(result)`) | 엔티티를 밖으로 내보내지 않는다 |
| 비즈니스 에러 코드 enum | 서비스의 domain 또는 global | 공통 형식(`ErrorCode` 인터페이스)은 `common-response`, 코드 값은 서비스별 (SA 11-2) |
| 이벤트 봉투·이벤트 클래스 | `common` 모듈 | 서비스 간 계약 |

### 7. reservation-service 안의 모듈 경계

reservation-service는 `concert / queue / reservation / lottery / notification` 다섯 모듈이 각각 4계층을 가진다(모듈러 모놀리스). 모듈끼리는 **상대의 application만 호출**한다.

- 허용: `reservation.application.ReservationService` → `concert.application.ScheduleQueryService`
- 금지: `reservation.application` → `concert.infrastructure.SeatJpaRepository`, `reservation.domain.Reservation` → `concert.domain.Seat` 직접 참조(ID로만 연결)

이렇게 해 두면 나중에 lottery를 별도 서비스로 떼어 낼 때 application 호출을 HTTP나 Kafka로 바꾸기만 하면 된다(결정 기록 #9).

### 8. 계층별 테스트

| 계층 | 테스트 | 도구 |
|---|---|---|
| domain | 순수 단위 테스트. 스프링 없이 상태 전이·계산 확인 | JUnit + AssertJ (가장 빠르고 가장 많이) |
| application | 유스케이스 흐름. 저장소는 실제 DB 또는 가짜 구현 | `@SpringBootTest` + Testcontainers, 또는 가짜 저장소 |
| infrastructure | SQL·Lua가 실제로 막는지(동시 요청, 부분 유니크) | Testcontainers PostgreSQL·Redis (H2 금지: 부분 인덱스 문법) |
| presentation | 요청 검증, 응답 형식, 상태 코드 | `@WebMvcTest` + MockMvc |

## 이 프로젝트에서는
- 뼈대: 각 서비스의 `presentation / application / domain / infrastructure / global`과 패키지마다 규칙을 적은 `package-info.java` (커밋 `867b416`).
- 근거: 결정 기록 #22, SA MASTER 13장, AI 판단 기록 `docs/ai-judgments/kangmin/2026-10-26-layered-architecture-four-layers.md`.

## 확인할 질문
- 계층 규칙을 사람 리뷰에만 맡길지, ArchUnit 테스트로 강제할지(예: domain 패키지는 infrastructure·presentation을 import하면 실패).
- 단순 CRUD 모듈(concert 관리자 등록 등)에서 저장소 방법 B를 허용할지.
- 서비스별 에러 코드 enum을 domain에 둘지 global에 둘지.

## 면접 포인트
- "왜 3계층이 아니라 4계층?": Service에 규칙과 기술 호출이 섞이는 것을 막으려고 domain을 분리했고, 저장소 인터페이스를 domain에 두어 의존을 역전했다. 대가로 인터페이스와 변환 코드가 늘었다.
- "도메인 엔티티에 JPA 애너테이션을 쓰면 순수하지 않은데?": 맞다. 도메인 모델과 영속 모델을 나누면 매핑 코드가 두 배라 일정상 타협했다. 대신 Kafka·Redis·HTTP 코드는 domain에 들어가지 않게 지켰다.
- "동시성 방어는 어느 계층?": 규칙의 정의는 domain, 실제 방어는 infrastructure의 조건부 UPDATE와 부분 유니크 인덱스. application은 이들을 한 트랜잭션으로 묶는다.

## 참고
- SA MASTER 8-1(일반 예매 시퀀스), 12-7(선점 쿼리), 13장(패키지 구조)
- 결정 기록 #9(서비스 3개 + 모듈화), #22(4계층), #59(조건부 UPDATE + 부분 유니크)
