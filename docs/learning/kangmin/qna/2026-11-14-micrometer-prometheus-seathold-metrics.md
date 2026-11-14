---
title: 좌석 선점에 붙인 Prometheus 지표는 코드에서 어떻게 만들어지고 수집되나(ISE-277, ISE-17)
date: 2026-11-14
updated: 2026-11-14
asker: GGangMY
level: 입문
tags: [micrometer, prometheus, grafana, observability, histogram, seathold]
---

# 좌석 선점에 붙인 Prometheus 지표는 코드에서 어떻게 만들어지고 수집되나(ISE-277, ISE-17)

## 질문
Prometheus 서버 지표를 위한 코드가 어떻게 적용됐는지, 최근 좌석 예매 쪽에 붙인 것을 코드 단위로 설명해 달라.

## 짧은 답
- 앱은 지표를 **보내지 않는다**. 앱 안의 Micrometer `MeterRegistry`가 숫자를 메모리에 들고 있고, Prometheus가 5초마다 `GET :9080/actuator/prometheus`로 **긁어 간다**(pull 방식).
- 좌석 선점에는 두 종류가 있다.
  1. 자동 지표 `http.server.requests`: Spring이 모든 HTTP 요청의 시간을 재 준다. ISE-277은 설정 한 줄로 여기에 **지연 버킷**(25ms~3s)을 켰다.
  2. 직접 만든 지표 `seathold.result`(API 결과별 카운터), `seathold.consumer.result`·`seathold.consumer.delay`(접수형 소비자 결과·지연).
- Grafana는 Prometheus에 PromQL을 보내 p95, 결과별 초당 응답, 300ms 안 비율을 그린다(`infra/grafana/dashboards/seathold.json`).

## 자세히

### 1. 전체 흐름
```
요청 → SeatHoldController → metrics.record(HELD)  ─┐
         (Spring이 요청 시간도 자동 측정)            ├→ MeterRegistry(메모리의 숫자들)
Kafka → SeatHoldRequestProcessor → metrics.record ─┘        │
                                                             ▼
                          /actuator/prometheus (관리 포트 9080, 텍스트 응답)
                                                             ▲ 5초마다 GET
                                   Prometheus(시계열 DB) ── Grafana(PromQL로 그림)
```

| 연결 고리 | 위치 |
|---|---|
| 의존성: actuator(엔드포인트) + `micrometer-registry-prometheus`(Prometheus 형식 출력) | `build.gradle` 137·139행 |
| 엔드포인트 노출과 공통 태그 `application` | `config-repo/application.yml` 1~10행 |
| 수집 대상과 주기(5s) | `infra/prometheus.yml` 2·16~19행 |

### 2. 직접 만든 카운터: `SeatHoldMetrics`
`reservation-service/.../seathold/observability/SeatHoldMetrics.java`

```java
public SeatHoldMetrics(MeterRegistry registry) {
    for (var outcome : Outcome.values()) {
        counters.put(outcome, Counter.builder("seathold.result")
                .tag("outcome", outcome.label)
                .register(registry));
    }
}
```
- `MeterRegistry`는 Spring Boot가 빈으로 만들어 둔다. 생성자로 받으면 된다.
- 이름 하나에 태그 값이 9개면 시계열(숫자 줄)이 9개다. 기동 때 9개를 미리 0으로 등록해 두면 아직 한 번도 안 일어난 결과도 그래프에 0으로 보인다(테스트 `결과_카운터는_요청이_없어도_모든_결과가_0부터_보인다`).
- `increment()`는 내부적으로 원자적 덧셈 하나라 요청 경로에 넣어도 비용이 거의 없다.

컨트롤러에서는 성공·실패를 감싸 한 번만 센다.
```java
try {
    var response = holdOnce(...);
    metrics.record(status == 201 ? HELD : ACCEPTED);
    return response;
} catch (RuntimeException e) {
    metrics.recordFailure(e);   // BusinessException 오류 코드 → outcome으로 바꿈
    throw e;                     // 응답 처리는 기존 예외 처리기에 맡김
}
```
왜 따로 만들었나: 판매 전·매수 초과·좌석 충돌·결제 불가가 전부 409라 HTTP 상태만으로는 "좌석 충돌"을 따로 볼 수 없다.

### 3. 라벨에 ID를 넣지 않는 이유(카디널리티)
Prometheus는 **이름 + 라벨 조합 하나마다 시계열 하나**를 저장한다. `userId`를 라벨로 넣으면 사용자 수만큼 시계열이 생겨 메모리가 터진다. 그래서 라벨은 `outcome`처럼 값이 몇 개로 정해진 것만 쓴다. HTTP 지표도 `uri`에 실제 ID가 아니라 `{scheduleId}` 템플릿이 들어간다(테스트가 확인).

### 4. 자동 지표에 버킷 켜기: 설정 한 줄
`config-repo/reservation-service.yml`
```yaml
management.metrics.distribution.slo:
  http.server.requests: 25ms,50ms,100ms,200ms,300ms,500ms,1s,3s
```
- 원래 `http.server.requests`는 개수(`_count`)·합(`_sum`)·최대(`_max`)만 내보낸다. 평균은 되지만 p95는 못 구한다.
- SLO 버킷을 켜면 "25ms 이하 몇 건, 50ms 이하 몇 건 …"(누적)을 `_bucket{le="0.025"}` 같은 줄로 더 내보낸다.
- 선택지 비교: `percentiles-histogram: true`(버킷 약 70개, 정밀) vs 고정 SLO 버킷 8개. 버킷 하나가 URI·상태 조합마다 시계열 하나라 8개를 골랐다. 대가는 버킷 사이 값은 추정이라는 점(예: 실제 p95 230ms가 200~300 사이 직선 보간으로 나온다).

### 5. 소비자 지표: Timer와 버킷
`SeatHoldConsumerMetrics.java`
```java
Timer.builder("seathold.consumer.delay")
     .serviceLevelObjectives(100ms, 500ms, 1s, 3s, 10s, 20s, 30s)
     .register(registry);
...
metrics.recordDelay(Duration.between(createdAt, now));  // SeatHoldRequestProcessor 129행
```
접수(202)부터 소비자가 처리를 시작하기까지 걸린 시간이다. 시한이 30초라 그 근처를 나눠 볼 수 있게 버킷을 잡았다. 결과 카운터 `seathold.consumer.result`는 실제로 상태를 닫은 경우에만 센다(`closed.get()`이 참일 때만 `record`, 90~91행). 중복 전달로 두 번 세지 않으려는 것이다.

### 6. Prometheus에 실제로 보이는 모습
Micrometer는 이름의 `.`을 `_`로 바꾸고 단위·접미사를 붙인다.

| 코드의 이름 | `/actuator/prometheus`에 나오는 줄 |
|---|---|
| Counter `seathold.result` | `seathold_result_total{application="reservation-service",outcome="held"} 42.0` |
| Timer `http.server.requests` | `http_server_requests_seconds_bucket{uri="...",le="0.3"} 950` / `_count` / `_sum` |

카운터는 계속 늘기만 한다. "초당 몇 건"은 Prometheus가 `rate()`로 기울기를 계산해서 얻는다.

### 7. Grafana 쿼리 읽기
| 패널 | PromQL | 뜻 |
|---|---|---|
| 선점 p95 | `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{uri="…/reservations",method="POST"}[1m])))` | 최근 1분 버킷별 증가율 → 서버(여러 대) 합산 → 95% 지점 추정 |
| 결과별 초당 응답 | `sum by (outcome) (rate(seathold_result_total[1m]))` | outcome마다 초당 건수 |
| 300ms 안 비율 | `…bucket{le="0.3"}` ÷ `…_count` | 누적 버킷이라 나누기 한 번으로 SLO 달성률 |

### 8. 테스트로 계약 지키기
`SeatHoldMetricsApiTest`는 실제 HTTP로 201·409·400을 만든 뒤 카운터가 1씩 늘었는지, 버킷 경계가 정확히 8개인지 확인한다. 대시보드가 이름·라벨로 지표를 찾기 때문에 이름이 바뀌면 그래프가 조용히 비는데, 그걸 테스트로 막는다.

## 확인할 질문
- 서버 쪽 p95와 k6가 잰 p95는 왜 다를 수 있나(네트워크·Gateway 시간 포함 여부)
- 지연을 재고 싶은 곳이 HTTP가 아니면 `@Timed`와 `Timer.record()` 중 무엇이 나은가

## 출처
- 코드: `SeatHoldMetrics.java`, `SeatHoldConsumerMetrics.java`, `SeatHoldController.java`, `SeatHoldRequestProcessor.java`, `config-repo/*.yml`, `infra/prometheus.yml`, `infra/grafana/dashboards/seathold.json`
- 커밋: `124674e9`(ISE-277, `#600`), `0f69c1aa`(ISE-17, `#609`)
- 팀 문서: `docs/design/reservation/seathold-metrics.md`, `docs/learning/kwonwoo/qna/2026-11-13-ise277-metrics-performance-impact.md`
