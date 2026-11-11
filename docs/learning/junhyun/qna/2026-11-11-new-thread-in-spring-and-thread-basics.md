---
title: 스프링 안에서 new Thread(Runnable)로 스레드를 직접 만들어도 되나, 스레드란 무엇인가
date: 2026-11-11
updated: 2026-11-11
asker: Junhyun An
level: 중급
tags: [thread, runnable, thread-local, race-condition, volatile, daemon-thread, executor, thread-pool-executor, async, scheduled, smart-lifecycle, transactional, mdc, virtual-thread, spring-boot]
---

# 스프링 안에서 new Thread(Runnable)로 스레드를 직접 만들어도 되나, 스레드란 무엇인가

## 질문
`new Thread(Runnable 작업)` 같은 식으로 스프링 안에서 스레드를 임의로 만들 수 있나? 스레드 개념도 같이 설명해 달라.

이어서 물은 것: 그럼 가급적 안 부르는 게 맞는 것 아닌가? 이 프로젝트 코드도 쓰지 않는 방향으로 고치는 게 나은가?(8절)

## 짧은 답
- **만들 수 있다.** 스프링은 일반 Java 코드를 막지 않으므로 컴파일도 실행도 된다.
- **다만 요청 처리 코드 안에서 `new Thread(...).start()`를 바로 부르는 것은 피한다.** 이렇게 만든 스레드에는 다섯 가지가 빠진다. 개수 상한, 재사용, 스프링 종료와 맞춘 정리, 예외 기록, 그리고 트랜잭션·로그 traceId·로그인 정보 같은 문맥이다.
- **스프링에서 쓰는 방식**은 두 가지다. 하나는 실행기(`TaskExecutor`) 빈에 일을 맡기는 것(`@Async`)이다. 다른 하나는 컴포넌트가 실행기를 직접 소유하고 `SmartLifecycle`로 시작·종료를 스프링에 맞추는 것이다. 이때 `new Thread`는 실행기에 스레드를 만들어 주는 `ThreadFactory` 안에서만 쓴다. 이 프로젝트의 Outbox 릴레이와 결제 배경 작업이 이 방식이다.
- **가상 스레드(Java 21)는 예외다.** 풀에 담지 않고 일마다 새로 만드는 것이 권장 사용법이라 `Thread.ofVirtual().start(...)`를 직접 불러도 된다. 대신 문맥 복사와 뒤쪽 자원 상한은 직접 챙긴다.

## 자세히

### 1. 스레드란
**프로그램, 프로세스, 스레드**
- 프로그램은 디스크에 있는 코드다(`reservation-service.jar`).
- 프로세스는 실행 중인 프로그램이다. OS가 프로세스마다 메모리 공간을 따로 준다. 컨테이너 하나에 JVM 프로세스 하나가 떠 있다.
- 스레드는 프로세스 안에서 코드를 실제로 한 줄씩 실행하는 흐름이다. 한 프로세스 안에 스레드가 여러 개 있을 수 있고, CPU가 실행하는 단위는 스레드다.

**스레드끼리 함께 쓰는 것과 각자 갖는 것**
```
┌──────────────── JVM 프로세스 (메모리 공간 하나) ────────────────┐
│  힙(Heap): new로 만든 객체, static 필드  ← 모든 스레드가 함께 쓴다 │
│                                                                 │
│  ┌ 스레드 A ─────┐  ┌ 스레드 B ─────┐  ┌ 스레드 C ─────┐         │
│  │ 스택: 지역 변수 │  │ 스택           │  │ 스택           │  ← 각자  │
│  │ 메서드 호출 기록│  │                │  │                │         │
│  │ PC: 다음 명령   │  │ PC             │  │ PC             │         │
│  └───────────────┘  └───────────────┘  └───────────────┘         │
└─────────────────────────────────────────────────────────────────┘
```
- 지역 변수는 스레드마다 따로라 안전하다. 힙의 객체(싱글턴 빈의 필드 포함)는 여러 스레드가 동시에 만질 수 있어 조심해야 한다(3절).
- 스프링 빈은 기본이 싱글턴이다. 요청 스레드 200개가 같은 서비스 객체 하나를 동시에 쓴다. 그래서 빈에는 요청마다 바뀌는 값을 필드로 두지 않는다.

**왜 여러 개를 쓰나**
- 동시에 여러 요청을 받으려고 쓴다. Tomcat은 요청 하나에 스레드 하나를 붙인다(요청당 스레드 모델).
- 기다리는 동안 다른 일을 하려고 쓴다. 한 스레드가 DB 응답을 기다리는 동안 다른 스레드가 CPU를 쓴다.
- 몇 개가 적당한지는 `docs/learning/junhyun/qna/2026-11-11-thread-pool-size-cpu-vs-io-bound.md`에 정리했다.

**플랫폼 스레드와 가상 스레드**
| 종류 | OS 스레드와의 관계 | 만드는 비용 | 쓰는 법 |
|---|---|---|---|
| 플랫폼 스레드 | Java 스레드 1개 = OS 스레드 1개 | 크다(OS 호출, 스택 예약) | 풀에 담아 재사용한다 |
| 가상 스레드(Java 21) | 많은 가상 스레드를 적은 OS 스레드(캐리어) 위에 번갈아 올린다 | 작다(JVM 안의 객체) | 일마다 새로 만들고 풀에 담지 않는다 |

### 2. Java로 스레드 만들기

**`Runnable`, `Thread`, 그리고 `start()`와 `run()`**
```java
Runnable task = () -> System.out.println(Thread.currentThread().getName());

Thread thread = new Thread(task, "worker-1");
thread.run();   // "main"이 찍힌다. 새 스레드가 아니라 지금 스레드에서 메서드를 부른 것뿐이다
thread.start(); // "worker-1"이 찍힌다. JVM이 새 스레드를 만들고 그 스레드에서 run()을 부른다
```
- `Runnable`은 "할 일"이고, `Thread`는 "그 일을 실행할 일꾼"이다. 일과 일꾼을 나누는 것이 실행기(5절)로 이어지는 출발점이다.
- `start()`는 한 스레드에 한 번만 부를 수 있다. 두 번 부르면 `IllegalThreadStateException`이다.

**결과가 필요하면 `Callable`과 `Future`**
```java
ExecutorService executor = Executors.newFixedThreadPool(4);
Future<Integer> future = executor.submit(() -> 1 + 1); // Callable: 값을 돌려주고 checked 예외를 던질 수 있다
Integer result = future.get();                          // 끝날 때까지 기다렸다가 값을 받는다
```

**스레드의 상태(`Thread.State`)**
| 상태 | 뜻 | 스레드 덤프에서 볼 때 |
|---|---|---|
| `NEW` | 만들었지만 `start()` 전 | |
| `RUNNABLE` | 실행 중이거나 CPU 차례를 기다림 | 소켓에서 응답을 기다리는 블로킹 I/O도 `RUNNABLE`로 보인다. CPU를 쓰고 있다는 뜻이 아니다 |
| `BLOCKED` | `synchronized` 잠금을 기다림 | 많으면 잠금 경합이다 |
| `WAITING` | 깨워 줄 때까지 기다림(`wait`, `join`, `park`) | 풀의 쉬는 스레드는 보통 이 상태다 |
| `TIMED_WAITING` | 시한을 두고 기다림(`sleep`, 시한 있는 `wait`·`park`) | |
| `TERMINATED` | `run()`이 끝남 | |

**데몬 스레드와 사용자 스레드**
- JVM은 데몬이 아닌 스레드(사용자 스레드)가 모두 끝나야 종료된다. `new Thread`의 기본은 만든 스레드를 따라가는데, `main`에서 만들면 사용자 스레드다.
- 사용자 스레드가 무한 루프를 돌면 애플리케이션을 꺼도 JVM이 내려가지 않는다.
- `setDaemon(true)`로 만든 스레드는 JVM이 내려갈 때 하던 일 중간에 그냥 끊긴다. 그래서 데몬으로 만들고, 정리는 스프링 종료 단계에서 따로 한다(이 프로젝트 방식, 아래 "이 프로젝트에서는").

### 3. 스레드가 메모리를 함께 쓸 때 생기는 문제
**경쟁 상태(race condition)**
```java
class Counter {
    int count;
    void increment() { count++; } // 읽기 → 1 더하기 → 쓰기, 세 단계다
}
// 스레드 두 개가 1만 번씩 부르면 2만이 아니라 그보다 작은 값이 나온다
```
두 스레드가 같은 값을 읽고 각자 1을 더해 쓰면 한 번이 사라진다.

| 도구 | 해결하는 것 | 비용 |
|---|---|---|
| `synchronized` | 한 번에 한 스레드만 들어가게 한다(원자성 + 가시성) | 기다리는 스레드는 `BLOCKED` |
| `AtomicInteger` 등 | 읽기·더하기·쓰기를 CPU 명령 하나로 처리한다 | 숫자 하나에만 쓴다 |
| `volatile` | 한 스레드가 쓴 값을 다른 스레드가 바로 보게 한다(가시성만) | `count++` 같은 복합 연산은 못 막는다 |

- 가시성 문제는 CPU 캐시와 컴파일러 최적화 때문에 생긴다. 한 스레드가 `stopping = true`를 써도, `volatile`이 없으면 다른 스레드가 오래된 `false`를 계속 볼 수 있다.
- 서버가 여러 대이면 JVM 안의 잠금은 소용이 없다. 그때는 DB 잠금이나 Redis 같은 바깥 장치로 막는다(이 프로젝트의 좌석 선점이 그렇다).

### 4. `ThreadLocal`: 스프링이 스레드에 묶어 두는 것들
`ThreadLocal`은 스레드마다 따로 보관하는 변수다. 같은 `ThreadLocal`을 읽어도 스레드마다 다른 값이 나온다. 스프링은 요청 하나가 스레드 하나에서 처리된다는 전제로 여러 문맥을 여기에 둔다.

| 문맥 | 보관하는 곳 | 새 스레드에서 일어나는 일 |
|---|---|---|
| 트랜잭션과 DB 커넥션 | `TransactionSynchronizationManager` | 바깥 `@Transactional`이 보이지 않는다. 새 스레드의 DB 작업은 다른 트랜잭션(또는 트랜잭션 없음)이다 |
| 로그 traceId | SLF4J `MDC` | 로그에 traceId가 빠져 요청 하나의 로그를 이어 볼 수 없다 |
| 로그인 정보 | `SecurityContextHolder`(Spring Security를 쓸 때) | 인증 정보가 비어 있다 |
| 현재 HTTP 요청 | `RequestContextHolder` | request 범위 빈에 접근하면 예외가 난다 |

**`@Transactional` 안에서 새 스레드를 만들면**
```java
@Transactional
public void reserve(Reservation reservation) {
    reservationRepository.save(reservation);
    new Thread(() -> notifier.notifyReserved(reservation.getId())).start(); // 문제가 되는 코드
}
```
- 새 스레드는 아직 커밋되지 않은 예약을 다른 트랜잭션에서 읽으므로 못 찾을 수 있다.
- 바깥 트랜잭션이 롤백돼도 알림은 이미 나갔다.
- 해결은 커밋 뒤에 일을 넘기는 것이다. `@TransactionalEventListener(phase = AFTER_COMMIT)`와 `@Async`를 함께 쓰거나, 이 프로젝트처럼 같은 트랜잭션에 Outbox 행을 쓰고 별도 스레드가 커밋된 행만 보낸다.

**풀에서 `ThreadLocal`을 지우지 않으면**
풀의 스레드는 일이 끝나도 죽지 않고 다음 일을 받는다. 앞 일이 넣은 `ThreadLocal` 값을 지우지 않으면 다음 일이 남의 traceId나 인증 정보를 보게 된다. 그래서 문맥을 복사해 넣는 코드는 `finally`에서 반드시 지운다.

### 5. 스프링 안에서 `new Thread(...).start()`를 바로 부르면 빠지는 것
| 빠지는 것 | 일어나는 일 | 스프링 방식에서는 |
|---|---|---|
| 개수 상한 | 요청 1,000개가 오면 스레드 1,000개가 생긴다. 메모리와 문맥 교환 비용이 커지고 DB 같은 뒤쪽 자원에 한꺼번에 몰린다 | 풀 크기와 큐 길이로 막고, 넘치면 거절 정책을 따른다 |
| 재사용 | 일마다 OS 스레드를 만들고 없앤다 | 풀의 스레드를 다시 쓴다 |
| 종료 관리 | 스프링이 꺼져도 스레드는 모른다. 사용자 스레드면 JVM이 안 내려가고, 데몬이면 하던 일 중간에 끊긴다 | 스프링 종료 단계에서 새 일을 멈추고 진행 중인 일을 정해진 시간만큼 기다린다 |
| 예외 기록 | 처리하지 않은 예외는 기본 처리기가 표준 오류에 스택만 찍고 스레드가 끝난다. 로그 형식과 지표에 남지 않는다 | 실행기 쪽에서 잡아 로그와 지표로 남긴다 |
| 문맥 | 4절의 문맥이 모두 빈다 | `TaskDecorator`나 문맥 전파 설정으로 복사한다 |
| 이름 | `Thread-0`, `Thread-1`이라 스레드 덤프에서 무슨 일인지 알 수 없다 | 접두사를 붙인다(`notify-1`) |

### 6. 스프링에서 비동기 일을 맡기는 방법

**A. 실행기 빈과 `@Async`**
`@EnableAsync`를 켜고 메서드에 `@Async`를 붙이면, 그 메서드는 부른 스레드가 아니라 실행기의 스레드에서 돈다.

Spring Boot 4.1.2가 기본으로 만드는 실행기(`applicationTaskExecutor`)의 설정값이다(`spring-boot-autoconfigure-4.1.2.jar`의 설정 메타데이터에서 확인).

| 설정 | 기본값 | 뜻 |
|---|---|---|
| `spring.task.execution.pool.core-size` | 8 | 평소 유지하는 스레드 수 |
| `spring.task.execution.pool.max-size` | 상한 없음 | 큐가 찼을 때 늘릴 수 있는 최대 스레드 수 |
| `spring.task.execution.pool.queue-capacity` | 상한 없음 | 스레드가 모두 바쁠 때 일을 쌓아 두는 큐 길이 |
| `spring.task.execution.thread-name-prefix` | `task-` | 스레드 이름 접두사 |
| `spring.task.execution.propagate-context` | `false` | 문맥(관측 정보 등)을 실행할 일에 넘길지 |
| `spring.task.execution.mode` | `auto` | 직접 만든 `Executor` 빈이 없을 때만 기본 실행기를 만든다(`force`면 있어도 만든다) |
| `spring.threads.virtual.enabled` | `false` | 켜면 풀 대신 일마다 가상 스레드를 만드는 실행기를 쓴다 |

`ThreadPoolExecutor`가 일을 받는 순서를 알아야 기본값의 함정이 보인다.
```
새 일이 들어온다
 1. 스레드 수 < core      → 새 스레드를 만들어 바로 실행한다
 2. core가 모두 바쁘다     → 큐에 넣는다
 3. 큐가 가득 찼다         → max까지 스레드를 더 만든다
 4. max도 모두 바쁘다      → 거절 정책을 따른다(예외, 부른 스레드가 직접 실행 등)
```
기본값은 큐가 무제한이라 3단계에 가지 않는다. 스레드는 8개에서 늘지 않고 일만 끝없이 쌓인다. 메모리가 차거나 일이 한참 늦게 실행된다. 그래서 실제로 쓸 때는 큐 길이와 최대 스레드 수를 정한 실행기를 만든다.

```java
@Configuration
@EnableAsync
class AsyncConfig {
    @Bean
    ThreadPoolTaskExecutor notificationExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100); // 큐에 상한을 둬야 max가 의미를 갖는다
        executor.setThreadNamePrefix("notify-");
        // 큐까지 가득 차면 부른 스레드가 직접 실행한다. 일이 버려지지 않고, 부르는 쪽이 자연스럽게 느려진다
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 요청 스레드의 MDC(traceId)를 실행 스레드에 복사하고, 끝나면 지운다
        executor.setTaskDecorator(task -> {
            var mdc = MDC.getCopyOfContextMap();
            return () -> {
                if (mdc != null) MDC.setContextMap(mdc);
                try {
                    task.run();
                } finally {
                    MDC.clear();
                }
            };
        });
        // 스프링이 꺼질 때 진행 중인 일을 최대 10초 기다린다
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}

@Service
class NotificationService {
    @Async("notificationExecutor")
    public void send(long reservationId) { /* 알림 전송 */ }
}
```

`@Async`에서 자주 틀리는 점이다.
- **같은 클래스 안에서 부르면 동기로 돈다.** `@Async`는 스프링이 빈 앞에 세운 프록시가 가로채서 동작한다. `this.send()`처럼 같은 객체 안에서 부르면 프록시를 거치지 않는다. `@Transactional`과 같은 원리다.
- **반환형이 `void`면 예외를 부른 쪽이 모른다.** `AsyncUncaughtExceptionHandler`로 받거나, `CompletableFuture`를 돌려준다.
- **`@Async` 메서드의 `@Transactional`은 새 트랜잭션이다.** 다른 스레드이기 때문이다(4절).

**B. `@Scheduled`**
- `@EnableScheduling`을 켜고 메서드에 `@Scheduled(fixedDelay = ...)`를 붙이면 주기적으로 돈다.
- Boot 기본 스케줄러의 스레드는 **1개**다(`spring.task.scheduling.pool.size=1`, 이름 `scheduling-1`). 모든 `@Scheduled` 작업이 이 스레드 하나를 나눠 쓴다. 한 작업이 오래 걸리면 다른 작업이 밀린다.

**C. 컴포넌트가 실행기를 직접 소유하고 `SmartLifecycle`로 수명을 맞추기**
- 실행기를 빈으로 등록하지 않고 컴포넌트 안에 둔다. `SmartLifecycle`의 `start()`에서 만들고 `stop()`에서 정리한다. 스프링이 컨텍스트를 띄우고 내릴 때 이 두 메서드를 불러 준다.
- 다른 작업과 스레드를 나눠 쓰지 않는 전용 스레드가 필요할 때 쓴다. 이 프로젝트의 Outbox 릴레이와 결제 배경 작업이 이 방식이다(아래).

**함께 알아 둘 함정: `CompletableFuture.supplyAsync(...)`에 실행기를 안 넘기면**
JVM 전체가 함께 쓰는 `ForkJoinPool.commonPool()`에서 돈다. 크기는 기본 CPU 수 - 1이다. 여기서 DB나 HTTP를 기다리면 같은 풀을 쓰는 다른 코드(병렬 스트림 등)까지 막힌다. I/O 일에는 두 번째 인자로 실행기를 넘긴다.

### 7. 가상 스레드는 직접 만들어도 되나
- JEP 444는 가상 스레드를 풀에 담지 말고 일마다 새로 만들라고 권한다. 만드는 비용이 작아 재사용할 이유가 없다. 그래서 `Thread.ofVirtual().start(...)`나 `Executors.newVirtualThreadPerTaskExecutor()`를 직접 쓰는 것이 정상적인 사용법이다.
- 풀이 없으므로 "개수 상한"도 없다. DB나 외부 API를 보호하려면 세마포어나 커넥션 풀로 동시 실행 수를 따로 막는다.
- 문맥은 여전히 따라오지 않는다. MDC 같은 값은 직접 복사하고 `finally`에서 지운다.
- Java 21에서는 `synchronized` 안에서 블로킹하면 가상 스레드가 캐리어에 고정(pinning)된다. JDK 24(JEP 491)에서 해결됐다.

### 8. 그럼 가급적 안 부르는 게 맞나
이어서 나온 질문이다. **맞다. 서비스·컨트롤러 같은 업무 코드에서는 `new Thread`를 부르지 않는 것을 기본으로 한다.** 다만 기준은 "`new Thread`라는 글자를 쓰지 않는다"가 아니다. **스레드의 개수와 수명을 누가 책임지는지 분명해야 한다**가 기준이다. 실행기나 스프링이 책임지면 쓰고, 아무도 책임지지 않으면 쓰지 않는다.

**`new Thread`를 써도 되는 자리**
| 자리 | 왜 괜찮나 | 이 프로젝트 예 |
|---|---|---|
| 직접 만든 실행기의 `ThreadFactory` 안 | 개수는 실행기가, 수명은 `SmartLifecycle`이 책임진다. 이름·데몬 여부를 정하려고 쓰는 것이다 | `OutboxRelayScheduler`, `BackgroundJobScheduler` |
| 종료 훅 | `Runtime.addShutdownHook`이 `Thread` 객체를 받는다 | `workspace-tools`의 정리 훅 |
| 일마다 만드는 가상 스레드 | 권장 사용법이다. 뒤쪽 자원 상한은 세마포어로 따로 둔다 | `AssistantController`의 스트림 |
| 테스트의 동시성 재현 | 여러 스레드가 같은 순간에 부딪히는 상황을 일부러 만든다. 테스트가 끝나면 풀을 닫는다 | `ReservationCancelApiTest.java:142-154`(같은 예매를 스레드 8개가 동시에 취소) |
| 스프링 없는 단독 프로그램 | 관리할 컨테이너가 없으므로 `main`이 직접 책임진다 | |

**스레드를 만들기 전에 거치는 순서**
"새 스레드를 어떻게 만들까"보다 앞선 질문이 있다. 대부분은 1~2단계에서 끝나고 스레드를 새로 만들 일이 없다.
1. **정말 따로 돌려야 하나?** 요청 스레드에서 그냥 끝내면 되는 일이 대부분이다. 비동기로 바꾸면 실패를 부른 쪽이 모르고, 순서·트랜잭션·테스트가 모두 어려워진다.
2. **서버가 죽어도 그 일이 남아야 하나?** 실행기의 큐는 메모리에 있다. 배포나 장애로 JVM이 내려가면 큐에 있던 일은 사라진다. 결제 결과 전달처럼 잃으면 안 되는 일은 DB(Outbox)나 Kafka에 남기고 다른 스레드나 서비스가 꺼내 처리한다. 이 프로젝트가 서비스 사이의 일을 넘길 때 쓰는 방식이다.
3. **같은 JVM 안에서 잠깐 따로 돌리면 되나?** 잃어도 괜찮은 일(캐시 미리 채우기 등)이면 상한을 둔 실행기와 `@Async`를 쓴다.
4. **다른 주기 작업과 섞이면 안 되나?** 전용 실행기를 컴포넌트가 소유하고 `SmartLifecycle`로 수명을 맞춘다. 이때 `new Thread`는 `ThreadFactory` 안에만 있다.
5. **일마다 오래 기다리는 I/O인가?** 가상 스레드를 쓰고, 뒤쪽 자원은 세마포어로 막는다.

**이 프로젝트에서 스레드를 직접 만드는 네 곳을 고쳐야 하나**
이어서 나온 질문이다. 결론은 **고치지 않는다**이다. 네 곳 모두 개수와 수명을 책임지는 쪽이 있다. `new Thread`라는 글자를 없애도 동작과 안전성은 그대로다.

| 위치 | 대안 | 판단 |
|---|---|---|
| Outbox 릴레이·결제 배경 작업의 `ThreadFactory` | Java 21 `Thread.ofPlatform().name(prefix + "-", 1).daemon(true).factory()`로 바꾸면 결제 쪽 `NamedThreadFactory` 클래스(번호 세는 `AtomicInteger` 포함)가 한 줄로 준다. JDK 21 Javadoc은 이 팩토리가 여러 스레드에서 함께 써도 안전하다고 적는다 | 동작이 같고 글자만 준다. 머지된 결제·공통 코드를 이것만으로 다시 건드릴 이유는 약하다. 그 파일을 다른 이유로 고칠 때 함께 바꿀 수 있는 정도다 |
| Outbox 릴레이·결제 배경 작업의 실행기 자체 | 스프링 `ThreadPoolTaskScheduler`를 빈이 아닌 내부 객체로 쓴다 | 종료 대기는 대신해 주지만, 실패 지표를 남기려면 `runTick`의 `Throwable` 처리는 그대로 남는다. 얻는 것이 적다 |
| assistant 스트림의 가상 스레드 | Boot 실행기(`applicationTaskExecutor`, 가상 스레드를 켜면 일마다 가상 스레드를 만든다)를 주입해 맡긴다. MDC 복사를 `TaskDecorator` 한 곳에 두고, 동시 스트림 상한을 설정으로 걸 수 있다 | 걱정할 만한 종료 문제는 이미 막혀 있다. Boot 4.1.2는 `server.shutdown` 기본값이 `graceful`이고, Tomcat 정상 종료가 진행 중인 비동기 요청 수(`getInProgressAsyncCount`)까지 확인해 기다린다(`spring-boot-tomcat-4.1.2.jar`의 `GracefulShutdown`). 단계별 시한은 기본 30초다. 관측된 문제가 없으므로 지금은 두고, 스트림 경로를 다시 손볼 때 후보로 본다 |
| assistant의 `static` 감시 실행기 | 스프링이 관리하는 스케줄러로 옮긴다 | 하는 일이 시한 넘은 스트림을 닫는 것뿐이고 데몬이라 JVM 종료를 막지 않는다. 두어도 된다 |

## 이 프로젝트에서는
스레드를 직접 만드는 운영 코드는 네 곳이다. 모두 요청 코드에서 `new Thread(...).start()`를 바로 부르지 않는다. `ThreadFactory` 안에서 만들거나 가상 스레드로 만든다. `@Async`는 쓰지 않는다.

| 위치 | 방식 | 왜 이렇게 했나(코드 주석·ADR 기준) |
|---|---|---|
| `common/.../outbox/OutboxRelayScheduler.java:116-123` | `ThreadFactory` 안 `new Thread(task, "outbox-relay")`, 데몬. `SmartLifecycle`로 시작·종료 | Spring 기본 스케줄러는 스레드가 1개라 PG 재조회가 느려지면 릴레이도 멈춘다는 검토(M4) 뒤 전용 실행기로 나눴다(`docs/decisions/0022-outbox-retry-and-retention.md`). `TaskScheduler` 빈을 만들면 Boot 기본 스케줄러가 빠지고 다른 `@Scheduled` 작업이 그 빈으로 옮겨 가 섞이므로 빈으로 등록하지 않는다 |
| `payment-service/.../job/BackgroundJobScheduler.java:123-137` | 같은 방식. 실행기 둘: `recovery`(재확인·환불, 스레드 2)와 `housekeeping`(보관 정리, 스레드 1) | 느린 정리 배치가 PG 재확인이나 환불을 늦추지 않게 실행기를 나눈다. 스레드 이름 `<접두사>-N`으로 구분한다 |
| `assistant-service/.../web/AssistantController.java:53` | 요청마다 `Thread.ofVirtual().name("assistant-stream").start(...)` | SSE로 답을 흘려보내는 동안 요청 스레드를 붙잡지 않는다. MDC를 복사해 넣고 `finally`에서 지운다. LLM 동시 호출 수는 `AssistantService.java:92`의 세마포어가 막는다 |
| `assistant-service/.../llm/AnthropicLlmClient.java:49-53` | `static` 단일 스레드 예약 실행기, 데몬 | 시한을 넘긴 스트림을 다른 스레드에서 닫는 일뿐이라 스레드 하나로 충분하다 |

개발 도구(`workspace-tools`)의 `new Thread`는 `Runtime.addShutdownHook`에 넘기는 종료 훅이다. 종료 훅은 `Thread` 객체를 받으므로 `new Thread`가 정석이다.

**Outbox 릴레이 코드에서 이 노트의 개념이 쓰인 곳**
- **수명 관리(5절):** `start()`에서 실행기를 만들고, `stop()`에서 새 라운드를 멈춘 뒤 `awaitTermination`으로 진행 중인 실행을 기다린다. 시간 안에 안 끝나면 `shutdownNow()`로 인터럽트한다.
- **데몬(2절):** `stop()`이 불리지 않는 경우에도 이 스레드가 JVM 종료를 막지 않는다.
- **예외 기록(5절):** `scheduleWithFixedDelay`는 작업이 예외로 끝나면 이후 실행을 모두 멈추고, 예외는 아무도 읽지 않는 `Future`에만 남는다. 그래서 `runTick`이 `Throwable`까지 잡아 ERROR 로그와 실패 지표로 남기고 다음 주기를 이어 간다.
- **가시성(3절):** `stopping`은 `volatile`이다. `stop()`을 부르는 스레드가 쓰고 `outbox-relay` 스레드가 읽기 때문이다.
- **트랜잭션 경계(4절):** 일을 넘기는 쪽은 같은 트랜잭션에 Outbox 행만 쓴다. 릴레이 스레드는 커밋된 행만 집어 Kafka로 보내므로 롤백된 일이 밖으로 나가지 않는다.

**reservation-service의 `@Scheduled` 9개는 스레드 1개를 나눠 쓴다**
- 입장(500ms), 좌석 선점 만료(5초), 회차 캐시 갱신(30초), 추첨 배정·마감·추첨·완료·승계 작업이 모두 Boot 기본 스케줄러를 쓴다. `spring.task.scheduling` 설정이 없고 가상 스레드도 꺼져 있어 스레드는 `scheduling-1` 하나다(6절 B).
- 추첨 실행이 길어지면 입장이 그동안 멈출 수 있다. 이 위험과 대응 후보(`pool.size`를 늘리거나 입장만 전용 스레드로 분리)는 `docs/design/infra/open-peak-load-plan.md` C1-2에 있고, 아직 측정하지 않았다.

## 면접 포인트
- **"`start()`와 `run()`의 차이는?"** `run()`은 지금 스레드에서 메서드를 부를 뿐이다. `start()`는 새 스레드를 만들고 그 스레드에서 `run()`을 부른다.
- **"스프링에서 `new Thread`를 직접 쓰면 안 되나요?"** 쓸 수는 있다. 하지만 개수 상한, 재사용, 종료 관리, 예외 기록, `ThreadLocal` 문맥이 빠진다. 실행기에 맡기고, 전용 스레드가 필요하면 `ThreadFactory`와 `SmartLifecycle`로 수명을 스프링에 맞춘다. Outbox 릴레이를 예로 든다.
- **"`@Transactional` 메서드 안에서 새 스레드로 DB 작업을 하면?"** 트랜잭션은 `ThreadLocal`에 묶여 있어 새 스레드는 다른 트랜잭션이다. 아직 커밋되지 않은 데이터를 못 보고, 롤백돼도 새 스레드의 작업은 되돌려지지 않는다. 커밋 뒤 이벤트나 Outbox로 넘긴다.
- **"`ThreadPoolExecutor`에서 max-size가 안 늘어나는 이유는?"** 큐가 무제한이면 큐가 차지 않아 core 이상 스레드를 만들지 않는다. Boot 기본 실행기도 큐가 무제한이다.
- **"`@Async`가 안 먹는 경우는?"** 같은 클래스 안에서 부를 때(프록시를 안 거친다), `@EnableAsync`가 없을 때, private 메서드일 때다.
- **"가상 스레드도 풀에 담아야 하나요?"** 아니다. 일마다 만든다. 대신 뒤쪽 자원은 세마포어로 보호한다.
- **"비동기로 처리하던 일이 서버 재시작 때 사라지면?"** 실행기 큐는 메모리에 있어 JVM이 내려가면 사라진다. 잃으면 안 되는 일은 같은 트랜잭션에 Outbox 행으로 남기고 별도 스레드가 커밋된 행을 보낸다. 스레드를 어떻게 만들지보다 일을 어디에 남길지가 먼저다.

## 참고
- Spring Boot 4.1.2 `spring-boot-autoconfigure-4.1.2.jar`의 `META-INF/spring-configuration-metadata.json`(`spring.task.*`, `spring.threads.virtual.enabled` 기본값과 설명을 직접 확인)
- Spring Framework 문서, "Task Execution and Scheduling": https://docs.spring.io/spring-framework/reference/integration/scheduling.html
- Spring Boot 문서, "Task Execution and Scheduling": https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html
- `java.util.concurrent.ThreadPoolExecutor` Javadoc(일을 받는 순서와 거절 정책)
- JEP 444 Virtual Threads: https://openjdk.org/jeps/444, JEP 491: https://openjdk.org/jeps/491
- 이 프로젝트 코드: `common/src/main/java/com/iseonjwa/common/messaging/outbox/OutboxRelayScheduler.java`, `payment-service/src/main/java/com/iseonjwa/payment/infrastructure/job/BackgroundJobScheduler.java`, `assistant-service/src/main/java/com/iseonjwa/assistant/web/AssistantController.java`
- 관련 노트: `docs/learning/junhyun/qna/2026-11-11-thread-pool-size-cpu-vs-io-bound.md`(스레드 수를 몇 개로 잡나)
