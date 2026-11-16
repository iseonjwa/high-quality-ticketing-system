---
title: 회차 findById는 왜 따로 필요했나, 잠금 조회(findByIdForUpdate)와 무엇이 다른가
date: 2026-11-02
asker: hyos1
level: 기초
tags: [jpa, lock, for-update, mvcc, repository, queue]
---

# 회차 findById는 왜 따로 필요했나, 잠금 조회(findByIdForUpdate)와 무엇이 다른가

## 질문
`findById`가 필요했던 이유는 `findByIdForUpdate`에 잠금이 걸려 있어서인가? 잠금 조회는 수정이 끝날 때까지 기다리니, 단순 조회는 잠금 없이 하려고 만든 것인가? 영은 님에게는 무엇을 왜 만들었다고 설명해야 하나?

## 답

### 1. 이유는 두 가지다
1. **읽는 메서드 자체가 없었다.** domain의 `ScheduleRepository`에는 `save`와 `findByIdForUpdate`만 있었다. Spring Data(`SpringDataScheduleRepository`)에는 `findById`가 있지만, 다른 모듈은 domain 인터페이스만 쓰므로 거기에 열어 줘야 쓸 수 있다.
2. **있는 잠금 조회를 대기열에서 쓰면 안 된다.** 질문의 이해가 맞다. 단순히 값을 읽기만 하면 되는데 잠금 조회를 쓰면, 수정할 것도 아니면서 다른 요청을 줄 세운다.

### 2. 잠금 조회가 대기열에서 문제인 이유
`findByIdForUpdate`는 `SELECT ... FOR UPDATE`다.

- 이 조회를 한 트랜잭션은 그 행의 쓰기 잠금을 **트랜잭션이 끝날 때까지** 쥔다.
- 다른 트랜잭션이 같은 행에 `FOR UPDATE`를 하면 앞 트랜잭션이 끝날 때까지 기다린다.

좌석 생성에서는 이게 목적이었다. 같은 회차에 좌석 생성이 두 번 들어오면 뒤 요청이 기다렸다가 "이미 만들었다"를 보게 했다.

대기열 입장은 티켓 오픈 순간 수천 명이 **같은 회차 행**을 동시에 읽는다. 여기에 잠금 조회를 쓰면 이렇게 된다.

```
사용자 1: 회차 31 잠금 획득 → Redis 입장 처리 → 커밋(잠금 해제)
사용자 2:          대기 ................................→ 잠금 획득 → ...
사용자 3:          대기 ......................................................→ ...
```

한 번에 한 명씩만 지나가니 대기열 앞에 DB 줄이 하나 더 생긴다.

### 3. 잠금 없는 조회는 기다리지 않는다
`findById`는 보통 `SELECT`다. PostgreSQL은 MVCC(행의 버전을 여러 개 두는 방식)라서 읽기는 다른 트랜잭션의 잠금을 기다리지 않는다. 누가 그 회차를 `FOR UPDATE`로 잡고 있어도 커밋된 마지막 값을 바로 읽는다. 수천 명이 동시에 읽어도 서로 막지 않는다.

| | `findById` | `findByIdForUpdate` |
|---|---|---|
| SQL | `SELECT ...` | `SELECT ... FOR UPDATE` |
| 다른 트랜잭션을 막나 | 아니다 | 같은 행을 잠그려는 트랜잭션을 막는다 |
| 기다리나 | 아니다 | 다른 트랜잭션이 잠금을 쥐고 있으면 기다린다 |
| 쓰는 곳 | 값을 읽기만 할 때(대기열 입장, 공연 조회) | 읽은 값으로 판단해 수정할 때(좌석 생성 중복 방지) |

### 4. 영은 님에게 드리는 것
값을 드리는 게 아니라 **값을 읽는 방법**을 드린다. `0`과 `지금 + 1일`은 회차를 읽을 방법이 없어서 넣은 임시값이다. 이제 회차마다 DB에 저장된 `open_at`과 `starts_at`을 읽어 넣으면 된다.

| Lua 인자 | 임시값 | 바꿀 값 |
|---|---|---|
| `openAtEpochMilli` | `0`(항상 열린 것으로 판정) | `schedule.getOpenAt().toEpochMilli()` |
| `keyExpireAtEpochSecond` | 지금 + 1일 | `schedule.getStartsAt().getEpochSecond()`(판매 종료 = 공연 시작) |

키 만료에 여유를 더할지, 없는 회차를 404로 돌려줄지는 대기열 담당인 영은 님이 정한다.

### 5. 후속 질문(영은 님): "concert 조회는 읽기 직접 허용"이면 schedules 테이블에 SQL을 직접 날려도 되나
SA 7-1의 모듈 규칙(결정 기록 #14)은 한 줄뿐이다: "reservation·lottery는 직접 호출 허용 / queue는 인터페이스로만 / notification은 Kafka 이벤트로만 / concert 조회는 읽기 직접 허용". SQL을 써도 되는지는 적혀 있지 않으므로 아래는 해석이다.

- "직접"은 이벤트나 별도 인터페이스를 거치지 않고 concert 모듈의 코드(`ScheduleRepository`, `Schedule`)를 바로 불러도 된다는 뜻으로 읽는다. 같은 DB를 쓰니 SQL도 돌아가지만, 테이블 구조를 다른 모듈 코드에 퍼뜨리는 것까지 허용한 말로 보지 않는다.
- "읽기"는 값을 바꾸지 않는다는 뜻이다. 회차를 바꾸는 일은 concert 모듈이 한다.
- 저장소를 쓰는 편이 나은 이유
  - 지운 회차 거르기(`@SQLRestriction`)가 자동으로 붙는다. 직접 SQL은 `deleted_at IS NULL`을 매번 써야 한다.
  - 컬럼 이름이나 판정 규칙이 바뀌어도 concert 모듈 한 곳만 고친다.
  - 비용은 같다. 둘 다 PK로 한 행을 읽는 `SELECT` 한 번이다.
- 선례: 좌석 선점(seathold)도 `concert.domain`의 `Schedule`·`ScheduleSeat`을 가져다 쓴다(ADR 0034, 단방향 의존).

### 6. 후속 질문: "별도 인터페이스 없이 바로 부른다"가 무슨 뜻인가
헷갈린 이유는 `ScheduleRepository`도 자바 `interface`이기 때문이다. 모듈 규칙의 "인터페이스"는 자바 키워드가 아니라 **모듈이 바깥에 따로 열어 둔 출입문**을 말한다.

모듈끼리 부르는 방법을 세 단계로 나눈 것이 #14 규칙이다.

| 규칙 | 뜻 | 코드 모양 |
|---|---|---|
| notification은 Kafka 이벤트로만 | 알림 코드를 부르지 않는다. 이벤트를 던지면 알림 모듈이 알아서 받는다 | `outbox.save(new ReservationConfirmed(...))` |
| queue는 인터페이스로만 | queue 안쪽 클래스(Redis 저장소, Lua 키)를 바로 만지지 않고, queue가 바깥용으로 만든 문 하나만 부른다 | `entryTokenVerifier.verify(token)` (queue가 공개한 것만) |
| concert 조회는 읽기 직접 | concert가 바깥용 문을 따로 만들 필요 없이, concert가 원래 쓰는 저장소·엔티티를 그대로 가져다 읽는다 | `scheduleRepository.findById(id)` |

"직접"이 아닌 경우였다면 concert 모듈이 대기열 전용 출입문을 하나 더 만들어야 한다.

```java
// 직접 허용이 아니었다면: concert가 바깥용 문을 따로 만든다
public interface ScheduleQueryPort {             // concert가 queue에게 열어 주는 문
    ScheduleTimes getTimes(Long scheduleId);     // 필요한 값만 담은 전용 객체
}

// 직접 허용이라서: concert 내부 저장소를 그대로 주입받는다
@RequiredArgsConstructor
public class QueueService {
    private final ScheduleRepository scheduleRepository;   // concert.domain.repository
    ...
    Schedule schedule = scheduleRepository.findById(scheduleId).orElseThrow(...);
}
```

concert 데이터는 거의 바뀌지 않고 여러 모듈이 읽기만 하므로, 출입문을 따로 만드는 수고를 덜려고 직접 읽기를 허용한 것이다. 대신 읽기만 한다. 값을 바꾸는 메서드(`save` 등)는 다른 모듈이 부르지 않는다.

### 7. 후속 질문: 원래는 FeignClient 같은 별도 인터페이스를 만들어야 하는데 조회만 생략한다는 뜻인가
방향은 맞다. 다만 FeignClient는 **서비스 사이**(다른 프로세스, HTTP)의 도구이고, queue와 concert는 **같은 서비스 안의 모듈**이다.

| 구분 | 예 | 실행 | 부르는 방법 |
|---|---|---|---|
| 서비스 사이 | reservation-service ↔ payment-service | 다른 프로세스, 다른 DB | HTTP(FeignClient 등)나 Kafka |
| 같은 서비스 안 모듈 사이 | queue ↔ concert (둘 다 reservation-service) | 같은 JVM, 같은 DB | 자바 메서드 호출 |

- reservation-service는 모듈러 모놀리스다(SA 7-1, #14). 한 서비스 안을 패키지(모듈)로 나눴을 뿐이라 HTTP를 거칠 이유가 없다.
- 그래도 모듈을 나눈 이유는 나중에 떼어 낼 수 있게 경계를 지키려는 것이다. 측정에서 필요해지면 queue를 별도 서비스로 분리한다(SA 7-1).
- 그래서 같은 서비스 안에서도 "출입문"을 둘 수 있다. 이때 출입문은 Feign이 아니라 자바 클래스(퍼사드)다. queue를 "인터페이스로만" 부르라는 규칙이 이것이다.
- concert는 읽기라면 이 출입문을 생략하고 `ScheduleRepository`를 그대로 주입받아도 된다. 나중에 queue를 서비스로 떼어 내면 그때 이 호출이 HTTP나 캐시로 바뀐다.
