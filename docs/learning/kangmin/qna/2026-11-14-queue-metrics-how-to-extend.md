---
title: 대기열에 Prometheus 지표를 붙이려면 코드를 어떻게 바꾸나(이미 있는 것, 비어 있는 것, 예시 코드와 문법)
date: 2026-11-14
updated: 2026-11-14
asker: GGangMY
level: 입문
tags: [micrometer, prometheus, queue, gauge, counter, timer, java-syntax]
---

# 대기열에 Prometheus 지표를 붙이려면 코드를 어떻게 바꾸나

## 질문
좌석 선점처럼 대기열에도 지표를 적용하려면 코드를 어떻게 바꿔야 하나. 상세한 주석, 설명, 문법까지.

## 짧은 답
- 대기열에는 **이미 상태 지표(Gauge) 5개가 있다**. 11/14 `#620`(ISE-61, 대기열 담당)으로 머지됐다. 대기 인원·입장 번호·발급 안 된 입장 자리·유효 토큰 수다.
- HTTP 지연·상태 코드도 이미 나온다. 대기열 API도 `reservation-service` 안에 있어서, ISE-277의 SLO 버킷 설정이 대기열 URI에도 그대로 적용된다.
- 비어 있는 곳은 **HTTP가 아닌 코드**, 즉 0.5초마다 도는 입장 처리 스케줄러다. 한 번 도는 데 얼마나 걸리는지, 몇 번 실패했는지가 지표에 없다. 붙인다면 여기가 1순위다.
- 결과 카운터는 좌석 선점만큼 필요하지 않다. 좌석 선점은 409 하나에 이유 4개가 섞여 있었지만, 대기열은 상태 코드와 결과가 거의 1:1로 맞는다(아래 3절).
- 대기열 코드는 대기열 담당의 영역이다. 실제로 바꿀 때는 담당과 먼저 맞춘다. 이 노트의 코드는 예시이고 저장소에 적용하지 않았다.

## 자세히

### 1. 지금 있는 것: `QueueMetrics`(Gauge)

`reservation-service/.../queue/observability/QueueMetrics.java`를 줄마다 풀면 이렇다.

```java
@Component                                   // 스프링 빈으로 등록. 생성자의 MeterRegistry를 Boot가 넣어 준다
public class QueueMetrics {
    // AtomicLong: 여러 스레드가 동시에 읽고 써도 깨지지 않는 long.
    // 스케줄러 스레드가 set()하고, Prometheus 요청 스레드가 get()한다
    private final AtomicLong waiting = new AtomicLong();

    public QueueMetrics(MeterRegistry meterRegistry) {
        Gauge.builder("queue.waiting",        // 지표 이름 → Prometheus에서는 queue_waiting
                      waiting,                 // 값을 들고 있는 객체
                      AtomicLong::get)         // 값을 꺼내는 함수(메서드 참조, 아래 6절)
             .description("아직 차례가 오지 않은 대기 인원")
             .register(meterRegistry);         // 등록. 이 줄이 있어야 /actuator/prometheus에 나온다
    }

    public void update(QueueStats s) {
        waiting.set(s.waiting());              // 덮어쓰기. Gauge는 "지금 값"이라 더하지 않는다
    }
}
```

호출하는 쪽은 `QueueAdmissionService.admitOnSale()`이다. 회차마다 `admit()`(입장 처리)을 한 뒤 읽기 전용 `stats.lua`로 숫자를 읽어 합친다. 한 회차도 실패하지 않았을 때만 `update()`한다. Redis 장애 중에 지표가 0으로 떨어져 보이는 것을 막으려는 것이다.

**Gauge는 언제 값을 읽나**: `set()`할 때가 아니라 **Prometheus가 긁어 갈 때**(5초마다) `AtomicLong::get`을 부른다. 그래서 0.5초마다 `set()`해도 Prometheus에는 5초 간격의 표본만 남는다.

### 2. Counter·Gauge·Timer 고르기
| 종류 | 무엇 | 예 | PromQL |
|---|---|---|---|
| Counter | 늘기만 하는 누적 횟수 | 선점 결과 수 | `rate(x_total[1m])` = 초당 |
| Gauge | 지금 이 순간의 값(오르내림) | 대기 인원 | 그대로, 여러 대면 `max` |
| Timer | 걸린 시간의 개수·합·최대(+버킷) | HTTP 지연, 스케줄러 1회 시간 | `histogram_quantile` |

대기 인원은 "지금 몇 명"이라 Gauge가 맞다. Counter로 들어온 수와 나간 수를 따로 세서 빼는 방법도 있지만, 서버가 재시작하면 카운터가 0부터 다시 시작해 차이가 어긋난다. 진실은 Redis에 있으니 Redis에서 직접 읽는 쪽이 정확하다.

**여러 대일 때 `max`**: 서버 2대가 같은 Redis를 읽어 같은 값을 낸다. `sum`하면 두 배가 된다. 반면 HTTP 지표는 서버마다 받은 요청이 달라서 `sum`이 맞다.

### 3. 결과 카운터가 필요한지 먼저 따져 보기
지표를 만들기 전에 "이 숫자로 무슨 질문에 답하나"를 먼저 정한다. 대기열 API의 상태 코드와 결과를 맞춰 보면 이렇다(`QueueController`, `QueueService`, `QueueErrorCode` 기준).

| API | 결과 | 상태 코드 | HTTP 지표로 구분되나 |
|---|---|---|---|
| `POST /queue` | 새로 줄 섬 | 201 | 된다 |
| | 이미 줄에 있음 / 이미 차례 옴(READY) | 200 / 200 | **안 된다** |
| | 판매 전·판매 끝 | 409 | 된다 |
| | 회차 없음 | 404 | 된다 |
| | Redis 혼잡 | 503 | 된다 |
| `GET /queue/me` | 대기 중 / 차례 옴 | 200 / 200 | **안 된다** |
| | 줄에 없음 | 404 | 된다 |
| `POST /queue/token` | 발급 / 차례 아님 / 줄에 없음 | 200 / 409 / 404 | 된다 |

좌석 선점은 409 하나에 판매 전·매수 초과·좌석 충돌·결제 불가 4개가 섞여 있어서 따로 셀 이유가 컸다. 대기열에서 HTTP로 안 보이는 것은 "200 중에 READY가 몇 건인가"뿐이고, 그것도 Gauge `queue.admitted.unissued`(차례가 왔는데 토큰을 안 받은 사람)로 상태를 이미 볼 수 있다. 그래서 결과 카운터는 우선순위가 낮다.

### 4. 비어 있는 곳: 입장 스케줄러(예시 코드)
`@Scheduled` 메서드는 HTTP 요청이 아니라서 `http.server.requests`에 잡히지 않는다. 알고 싶은 것은 두 가지다.
- 1회 처리 시간: `fixedDelay` 500ms 뒤에 다시 돈다. 처리가 300ms 걸리면 실제 주기는 800ms가 되어 입장 속도가 설정보다 느려질 수 있다(`admit.lua`가 시간 기준으로 계산하는지는 확인할 질문).
- 실패 횟수: 지금은 로그로만 남는다. 로그는 그래프와 경보로 쓰기 어렵다.

먼저 확인할 것: Spring Framework 6.1부터 `@Scheduled` 실행을 `tasks.scheduled.execution`이라는 관측(Observation)으로 기록한다. 이 프로젝트 테스트(`SchedulerRedisSpanConfigurationTest` 106행)에 그 이름이 나오지만, 배포 서버의 `/actuator/prometheus`에 Timer로 나오는지는 **확인 못 했다**(`docs/context/perf-blueprint-2026-11-13.md` 106행에도 미확인으로 적혀 있다). 나온다면 1회 시간은 새로 만들 필요가 없고, 실패 카운터만 만들면 된다. 로컬에서 서비스를 띄우고 `curl localhost:9080/actuator/prometheus | grep tasks_scheduled`로 확인한다.

나오지 않는다고 가정하면 예시 코드는 이렇다.

```java
package com.iseonjwa.reservation.queue.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 입장 처리 스케줄러 1회의 시간과 회차별 실패 수. 라벨에는 회차 ID를 넣지 않는다.
 */
@Component
public class QueueAdmissionMetrics {
    static final String DURATION = "queue.admission.duration";
    static final String FAILURE = "queue.admission.failure";

    // 실패 이유. 값의 종류가 정해져 있어 라벨로 써도 시계열이 늘지 않는다
    public enum Failure {
        REDIS_UNAVAILABLE("redis_unavailable"),   // 차단기가 열렸거나 Redis가 응답하지 않음
        ERROR("error");                           // 그 밖의 예외

        private final String label;               // enum 상수마다 붙는 값(아래 6절)

        Failure(String label) {                   // enum 생성자. 위 상수 괄호 안 값이 여기로 들어온다
            this.label = label;
        }
    }

    private final Timer duration;
    private final Map<Failure, Counter> failures = new EnumMap<>(Failure.class);

    public QueueAdmissionMetrics(MeterRegistry registry) {
        this.duration = Timer.builder(DURATION)
                .description("입장 처리 1회(판매 중인 모든 회차)에 걸린 시간")
                // 주기 500ms 근처를 나눠 본다. 버킷 하나가 시계열 하나다
                .serviceLevelObjectives(Duration.ofMillis(10), Duration.ofMillis(50), Duration.ofMillis(100),
                        Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1))
                .register(registry);

        // 기동할 때 모든 이유를 0으로 미리 등록한다. 아직 실패가 없어도 그래프에 0으로 보인다
        for (var failure : Failure.values()) {
            failures.put(failure, Counter.builder(FAILURE)
                    .description("입장 처리에 실패한 회차 수(이유별)")
                    .tag("reason", failure.label)
                    .register(registry));
        }
    }

    /** 작업을 실행하면서 걸린 시간을 잰다. 예외가 나도 시간은 기록된다. */
    public <T> T time(Supplier<T> work) {
        return duration.record(work);             // Timer가 시작·끝 시각을 재고 work의 반환값을 그대로 돌려준다
    }

    public void recordFailure(Failure failure) {
        failures.get(failure).increment();
    }
}
```

`QueueAdmissionService`에서는 세 군데만 바뀐다(`// +` 줄).

```java
@Service
@Slf4j
@RequiredArgsConstructor                          // final 필드를 받는 생성자를 Lombok이 만든다
public class QueueAdmissionService {
    private final ScheduleReader scheduleReader;
    private final QueueRepository queueRepository;
    private final QueueProperties queueProperties;
    private final QueueMetrics queueMetrics;
    private final QueueAdmissionMetrics admissionMetrics;            // + 필드만 추가하면 주입된다

    public void admitOnSale() {
        admissionMetrics.time(() -> {                                // + 전체를 감싸 1회 시간을 잰다
            QueueStats total = QueueStats.EMPTY;
            boolean failed = false;
            for (ScheduleTimes scheduleTimes : scheduleReader.findOnSale()) {
                try {
                    queueRepository.admit(/* 기존과 같음 */);
                    total = total.plus(queueRepository.stats(/* 기존과 같음 */));
                } catch (QueueStorageUnavailableException e) {
                    failed = true;
                    admissionMetrics.recordFailure(Failure.REDIS_UNAVAILABLE);   // +
                    log.warn("...", scheduleTimes.id());
                } catch (RuntimeException e) {
                    failed = true;
                    admissionMetrics.recordFailure(Failure.ERROR);               // +
                    log.error("...", scheduleTimes.id(), e);
                }
            }
            if (!failed) {
                queueMetrics.update(total);
            }
            return null;                         // Supplier라 값을 돌려줘야 한다. 돌려줄 값이 없어 null
        });
    }
}
```

**주의(람다 안의 지역 변수)**: 람다 안에서 `total = total.plus(...)`처럼 다시 대입하는 변수는 람다 **안에서** 선언해야 한다. 람다 밖에 선언한 지역 변수는 effectively final(한 번만 대입)이어야 람다가 쓸 수 있다. 위 예시에서 `total`·`failed`를 람다 안으로 옮긴 이유다.

**감싸는 방식 고르기**
| 방식 | 코드 | 장점 | 대가 |
|---|---|---|---|
| `timer.record(Supplier)` (예시) | `duration.record(() -> {...})` | 예외가 나도 기록, 실수할 틈 없음 | 메서드 전체가 람다 안으로 들어가 한 단계 깊어짐 |
| `Timer.Sample` | `var s = Timer.start(); try {...} finally { s.stop(duration); }` | 기존 코드 들여쓰기 유지 | `finally`를 빠뜨리면 예외 때 기록이 빠짐 |
| `@Timed` 애너테이션 | 메서드 위에 `@Timed("queue.admission.duration")` | 코드 한 줄 | AOP 설정(`TimedAspect` 빈)이 필요하고, SLO 버킷 지정이 설정 파일로 흩어짐 |

Grafana에서 볼 PromQL 예시:
```promql
# 입장 처리 1회 p95. 500ms(주기)에 가까워지면 실제 입장 속도가 설정보다 느려질 수 있다
histogram_quantile(0.95, sum by (le) (rate(queue_admission_duration_seconds_bucket[1m])))

# 분당 실패 회차 수(이유별). 경보 규칙의 재료
sum by (reason) (increase(queue_admission_failure_total[1m]))
```

### 5. 테스트 예시
지표 테스트는 스프링을 띄우지 않고 메모리용 `SimpleMeterRegistry`로 한다(기존 `QueueMetricsTest`와 같은 방식).

```java
class QueueAdmissionMetricsTest {
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();   // 테스트용 메모리 레지스트리
    final QueueAdmissionMetrics metrics = new QueueAdmissionMetrics(registry);

    @Test
    void 실패_이유는_처음부터_0으로_보인다() {
        for (var reason : List.of("redis_unavailable", "error")) {
            assertEquals(0.0, registry.get("queue.admission.failure").tag("reason", reason).counter().count());
        }
    }

    @Test
    void 작업_시간을_한_번_기록하고_반환값을_그대로_돌려준다() {
        var result = metrics.time(() -> "done");

        assertEquals("done", result);
        assertEquals(1, registry.get("queue.admission.duration").timer().count());
    }

    @Test
    void 작업이_예외를_던져도_시간은_기록된다() {
        assertThrows(IllegalStateException.class, () -> metrics.time(() -> { throw new IllegalStateException(); }));

        assertEquals(1, registry.get("queue.admission.duration").timer().count());
    }
}
```
`QueueAdmissionServiceTest`에는 "Redis 예외가 나면 `redis_unavailable`가 1 늘어난다"를 더한다. 변이 테스트로 `recordFailure` 줄을 지우면 이 테스트가 실패하는지도 확인한다.

### 6. 문법 정리
| 문법 | 예 | 뜻 |
|---|---|---|
| 빌더 패턴 | `Counter.builder(n).tag(k, v).description(d).register(r)` | 설정을 점(.)으로 이어 붙이고 마지막 `register`에서 만든다. 각 메서드가 자기 자신을 돌려줘서 이어 쓸 수 있다 |
| 메서드 참조 | `AtomicLong::get` | `x -> x.get()`의 줄임. "이 객체의 get을 불러라" |
| 람다 | `() -> { ...; return null; }` | 이름 없는 함수. `Supplier<T>`(인자 없음, 값 반환)에 맞춘 모양 |
| 제네릭 메서드 | `public <T> T time(Supplier<T> work)` | 호출할 때 T가 정해진다. `time(() -> "done")`이면 T는 String |
| 필드가 있는 enum | `REDIS_UNAVAILABLE("redis_unavailable")` + 생성자 | 상수마다 값을 붙인다. Java 이름(대문자)과 라벨(소문자 밑줄)을 나눈다 |
| `EnumMap` | `new EnumMap<>(Failure.class)` | 키가 enum인 Map. 내부가 배열이라 `HashMap`보다 빠르고 순서가 enum 선언 순이다 |
| `var` | `for (var f : Failure.values())` | 지역 변수 타입을 컴파일러가 추론한다(Java 10+) |
| `AtomicLong` | `set`, `get` | 락 없이 스레드 안전한 long. 쓰는 스레드(스케줄러)와 읽는 스레드(Prometheus)가 다르기 때문에 필요 |
| Lombok `@RequiredArgsConstructor` | 필드에 `final`만 붙이면 됨 | final 필드를 모두 받는 생성자를 만들어 준다. 스프링이 그 생성자로 주입한다 |

### 7. Gauge에서 흔히 하는 실수
- **약한 참조**: `Gauge.builder(name, obj, fn)`은 `obj`를 약한 참조(weak reference)로 잡는다. 생성자 안의 지역 변수로 `new AtomicLong()`을 넘기면 GC가 치운 뒤 값이 `NaN`이 된다. 그래서 `QueueMetrics`는 AtomicLong을 **필드**로 들고 있다.
- **이름 대소문자**: Micrometer는 점(.)만 밑줄로 바꾸고 대소문자는 그대로 둔다. `admittedUnissued`를 이름에 그대로 넣으면 `queue_admitted_admittedUnissued`가 된다(`#620` 구현 중 실제로 잡은 실수). 이름은 소문자와 점으로 쓴다.
- **회차 ID 라벨**: 회차마다 Gauge를 등록하면 판매가 끝난 회차를 지우는 코드까지 필요하다. `#620`은 판매 중인 회차를 합친 값 하나로 냈다.

## 확인할 질문
- `tasks_scheduled_execution_seconds`가 `/actuator/prometheus`에 나오나. 나오면 4절의 Timer는 빼고 실패 카운터만 남긴다
- `admit.lua`는 "주기당 N명"인가 "지난 시간 × 초당 N명"인가. 앞쪽이면 처리 시간이 늘 때 실제 입장 속도가 떨어진다
- 대기열 담당이 스케줄러 지표를 이미 계획하고 있나(ISE-61 남은 범위)

## 출처
- 코드: `queue/observability/QueueMetrics.java`, `queue/application/QueueAdmissionService.java`, `QueueService.java`, `QueueController.java`, `QueueErrorCode.java`, `QueueMetricsTest.java`
- 커밋: `1dc9337e`(`#620`, ISE-61), 판단 기록 `docs/ai-judgments/youngeun/2026-11-14-queue-metrics-design.md`
- 관련 노트: `qna/2026-11-14-micrometer-prometheus-seathold-metrics.md`, `qna/2026-11-14-spring-boot-actuator.md`
