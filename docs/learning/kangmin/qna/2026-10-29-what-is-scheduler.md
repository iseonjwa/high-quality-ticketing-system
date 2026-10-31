---
title: 스케줄러와 워커란 무엇이고, 추첨 설계에서 왜 5개나 필요한가
date: 2026-10-29
updated: 2026-10-29
asker: GGangMY
level: 기초
tags: [spring, scheduler, scheduled, skip-locked, lottery]
---

# 스케줄러와 워커란 무엇이고, 추첨 설계에서 왜 5개나 필요한가

## 질문
스케줄러가 뭐야? 워커는 뭐야? (추첨 설계 문서 `docs/design/lottery/details.md` 4절 "스케줄러와 워커"를 읽다가 나온 질문)

## 짧은 답
스케줄러는 사용자 요청 없이 정해진 시각이나 주기에 서버가 스스로 실행하는 작업이다. API는 "요청이 오면" 일하고, 스케줄러는 "시간이 되면" 일한다.
추첨은 마감·추첨·승계 마감처럼 "그 시각이 되면 상태가 바뀌어야 하는" 일이 많은데, 그 시각에 요청을 보내 줄 사용자가 없다. 그래서 서버가 짧은 주기로 "이제 바꿀 때가 된 행이 있나"를 DB에서 찾아 처리한다.
Spring에서는 `@Scheduled` 메서드로 만들고, 서버 여러 대가 같은 작업을 동시에 돌려도 결과가 한 번만 반영되게 조건부 UPDATE와 `SKIP LOCKED`로 막는다.

## 자세히

### 요청 기반 작업과 시간 기반 작업
| 구분 | 시작하는 쪽 | 예 |
|---|---|---|
| API | 클라이언트 요청 | 응모하기, 결과 조회 |
| 이벤트 소비자 | 다른 서비스가 보낸 메시지 | `payment.result`를 받아 예매 확정 |
| 스케줄러 | 시계(주기·시각) | 응모 마감 시각이 지나면 OPEN → CLOSED |

"응모 마감"은 누가 버튼을 누르는 일이 아니다. 마감 시각이 지났다는 사실만 있다. 이런 일은 스케줄러가 맡는다.

### Spring에서 만드는 법
```java
@Configuration
@EnableScheduling            // 스케줄링 기능을 켠다
class SchedulingConfig {}

@Component
class LotteryCloseScheduler {
    @Scheduled(fixedDelay = 10_000)   // 이전 실행이 끝나고 10초 뒤 다시 실행
    void close() {
        // UPDATE lotteries SET status = 'CLOSED'
        //  WHERE status = 'OPEN' AND entry_end_at <= now()
    }
}
```

주기 지정 방식:
| 속성 | 의미 | 쓰는 곳 |
|---|---|---|
| `fixedDelay` | 이전 실행이 **끝난 뒤** N 뒤 | 처리 시간이 들쭉날쭉한 작업. 실행이 겹치지 않는다 |
| `fixedRate` | 이전 실행이 **시작한 뒤** N 뒤 | 일정 간격이 중요한 작업. 처리가 길면 밀린다 |
| `cron` | 달력 기준 시각(예: 매일 03:00) | 정산, 통계 |

### 주의할 점 두 가지

**1. 서버가 여러 대면 모든 서버에서 돈다.**
`@Scheduled`는 인스턴스마다 실행된다. 서버 3대면 마감 작업도 3번 돈다. 선택지는 셋이다.

| 방법 | 방식 | 대가 |
|---|---|---|
| 분산 락(ShedLock 등) | 한 인스턴스만 실행하게 락을 잡는다 | 라이브러리·락 테이블 추가, 락 잡은 서버가 죽으면 락 만료까지 멈춘다 |
| 한 대에서만 켜기 | 설정으로 특정 인스턴스만 활성화 | 그 서버가 죽으면 작업도 멈춘다 |
| 여러 번 돌아도 안전하게 만들기 | 조건부 UPDATE, `FOR UPDATE SKIP LOCKED` | 쿼리를 조심해서 짜야 한다 |

추첨 설계는 세 번째를 골랐다. `WHERE status = 'OPEN'` 조건이 있으면 두 서버가 동시에 실행해도 한쪽만 1행을 바꾸고 다른 쪽은 0행을 바꾼다. 여러 행을 나눠 처리하는 배정 워커는 `SKIP LOCKED`로 다른 서버가 잡은 행을 건너뛰므로 같은 당첨자를 두 번 처리하지 않는다. 서버 하나가 죽어도 다음 주기에 다른 서버가 이어받는다.

**2. 스레드를 공유한다.**
Spring Boot가 자동으로 만드는 스케줄러 스레드 풀은 기본 1개다(`spring.task.scheduling.pool.size`). 한 작업이 오래 걸리면 다른 예약 작업이 늦어진다. 작업이 늘면 풀 크기를 키우거나 전용 실행기를 둔다.

### 스케줄러와 워커
문서에서 둘을 나눠 부르지만 기술은 같다(`@Scheduled`). 차이는 하는 일의 모양이다.
- 스케줄러: 시각 조건을 보고 상태를 한 번에 바꾼다(마감, 추첨, 승계 마감, 종결).
- 워커: 처리할 행이 쌓인 대기열을 짧은 주기로 조금씩 비운다(배정 워커가 당첨자 1명씩 예매를 만든다).

### 워커란
워커(worker)는 **쌓여 있는 일감을 하나씩 꺼내 처리하는 실행 단위**다. 백그라운드 작업자라고도 한다. 일감이 어디에 쌓이는지에 따라 모양이 갈린다.

| 일감이 쌓이는 곳 | 워커가 하는 일 | 예 |
|---|---|---|
| DB 테이블의 "처리 안 된 행" | 주기마다 조회해 집는다(폴링) | outbox 릴레이, 추첨 배정 워커 |
| 메시지 브로커(Kafka, RabbitMQ) | 메시지가 오면 받는다 | Kafka 소비자 |
| 메모리 큐 | 스레드가 큐에서 꺼낸다 | `ExecutorService`의 작업 스레드 |

"워커"는 기술 이름이 아니라 역할 이름이다. 스케줄러로 깨워도, Kafka 소비자로 만들어도, 일감 목록을 비우는 일을 하면 워커라고 부른다. "작업 큐 + 워커" 구조는 일을 받는 쪽(API)과 처리하는 쪽을 나눠, 요청은 빨리 끝내고 무거운 일은 뒤에서 속도를 조절하며 처리하게 한다.

DB 폴링 워커의 기본 순서:
1. 처리 대상 행을 조건으로 찾는다(`WHERE ... IS NULL`, `status = 'PENDING'`).
2. `FOR UPDATE SKIP LOCKED`로 잠근다. 다른 워커가 잡은 행은 건너뛴다.
3. 한 건(또는 한 묶음)을 처리하고 행 상태를 바꾼다. 상태가 바뀌었으니 다음 조회에 다시 걸리지 않는다.
4. 처리 중 서버가 죽으면 트랜잭션이 롤백되어 행이 그대로 남고, 다음 주기에 다른 워커가 집는다.

추첨 배정 워커(`docs/design/lottery/options.md` D10 B안)에 대입하면:
- 일감: 당첨됐지만 아직 예매가 없는 응모 행(`WON AND reservation_id IS NULL`).
- 처리 단위: 당첨자 1명 = 1트랜잭션. 매수 한도 확인 → 좌석 배정 → 예매 HELD 생성 → 응모 행에 `reservation_id` 연결 → 결제 요청 outbox 저장.
- 끝 표시: `reservation_id`가 채워지면 다음 조회 조건에서 빠진다.
- 왜 추첨 트랜잭션 안에서 2,000건을 한 번에 만들지 않나: 트랜잭션이 매우 길어지고 한 건 실패가 전체를 되돌린다. 워커로 나누면 실패가 건별로 격리되고, 승계로 새로 WON이 된 사람도 같은 워커가 처리한다. 대가는 트랜잭션이 2,000번 열린다는 것이고 처리 시간은 측정 전이다.

## 이 프로젝트에서는
- 이미 있는 예: `common/src/main/java/com/iseonjwa/common/messaging/outbox/OutboxRelayScheduler.java`. `@Scheduled(fixedDelayString = "${messaging.outbox.interval:500ms}")`로 0.5초마다 outbox 행을 Kafka로 보낸다. `OutboxRepository`가 `FOR UPDATE OF o SKIP LOCKED`로 행을 집어 여러 인스턴스가 나눠 처리한다. 코드 주석에 "기본 스케줄러 스레드를 다른 예약 작업과 공유한다"는 주의점과 전용 실행기 분리(ISE-40) TODO가 있다.
- `@EnableScheduling`은 `CommonOutboxConfiguration`에서 `messaging.outbox.relay-enabled=true`일 때만 켜진다. 추첨 스케줄러를 붙일 때 이 설정을 끄면 추첨 스케줄러도 함께 꺼지는지 확인해야 한다(확인할 질문).
- 추첨 설계안(`docs/design/lottery/details.md` 4절)의 5개:

| 이름 | 주기 | 하는 일 |
|---|---|---|
| 마감 | 10초 | 응모 마감 시각이 지난 추첨 OPEN → CLOSED |
| 추첨 | 10초 | 추첨 시각이 지난 추첨의 순위 계산, CLOSED → DRAWN |
| 배정 워커 | 1초 | 당첨자 1명씩 좌석 배정, 예매 HELD, 결제 요청 outbox |
| 승계 마감 | 10초 | 추첨 + 30분이 지나면 남은 예비자 → LOST |
| 종결 | 1분 | 끝나지 않은 당첨이 없으면 DRAWN → DONE |

주기가 10초면 마감이 최대 10초 늦게 반영된다. 그래도 응모 API가 INSERT 때 DB `now()`로 마감을 직접 검사하므로 늦은 응모는 들어오지 않는다. 스케줄러가 늦어도 결과가 바뀌지 않게 만드는 것이 설계의 핵심이다.

- outbox 릴레이도 워커다. `OutboxRepository.claimNext`가 `status = 'PENDING'` 행을 `FOR UPDATE OF o SKIP LOCKED`로 집으면서 `claimed_until`(임대 만료 시각)을 적는다. 보내다 서버가 죽으면 임대가 끝난 뒤 다른 인스턴스가 다시 집는다. 이 경우 같은 메시지가 두 번 갈 수 있어 소비자가 멱등해야 한다. 추첨 배정 워커는 한 트랜잭션 안에서 처리를 끝내므로 임대 없이 트랜잭션 롤백만으로 이어받는다.

## 면접 포인트
- **워커로 비동기 처리하면 무엇을 얻고 무엇을 치르나요?** 요청 경로가 짧아지고, 건별 실패가 격리되며, 처리 속도를 따로 조절할 수 있다. 대신 결과가 바로 나오지 않아 상태 조회가 필요하고, 같은 일감이 두 번 처리될 가능성을 멱등으로 막아야 한다.
- **서버를 여러 대 띄우면 `@Scheduled` 작업이 중복 실행되지 않나요?** 인스턴스마다 실행된다. 분산 락으로 한 대만 돌리거나, 작업 자체를 여러 번 돌아도 안전하게 만든다. 이 프로젝트는 조건부 UPDATE와 `SKIP LOCKED`로 후자를 택했다. 락 보유 서버가 죽었을 때 멈추는 문제가 없고 처리량을 인스턴스 수만큼 늘릴 수 있기 때문이다.
- **`fixedDelay`와 `fixedRate`의 차이는?** 끝난 뒤 기준과 시작한 뒤 기준이다. DB를 폴링하는 작업은 실행이 겹치지 않는 `fixedDelay`가 안전하다.
- **스케줄러 주기만큼 반영이 늦어지는 문제는?** 정확한 시각 판정은 요청 경로(INSERT 조건)에 두고, 스케줄러는 상태 정리만 맡겨 지연이 결과를 바꾸지 않게 한다.

## 참고
- Spring Framework 문서 "Task Execution and Scheduling" (`@Scheduled`, `@EnableScheduling`)
- Spring Boot 문서 "Task Execution and Scheduling" (`spring.task.scheduling.pool.size`, 기본 1)
- 코드 확인: `OutboxRelayScheduler.java`, `CommonOutboxConfiguration.java`, `OutboxRepository.java`
