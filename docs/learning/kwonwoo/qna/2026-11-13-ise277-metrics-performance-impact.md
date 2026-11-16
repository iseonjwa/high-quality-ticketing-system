---
title: ISE-277 서버 지표(HTTP 히스토그램, 선점 결과 카운터)를 더하면 선점 성능이 떨어지나
date: 2026-11-13
updated: 2026-11-13
asker: KWON-WOO
level: 중급
tags: [observability, micrometer, prometheus, histogram, cardinality, seathold]
---

# ISE-277 서버 지표(HTTP 히스토그램, 선점 결과 카운터)를 더하면 선점 성능이 떨어지나

## 질문
ISE-277(좌석 선점·공연 조회의 서버 쪽 지표 빈칸 채우기)을 하면 기존보다 성능에 영향이 가는가.

## 답
요청 경로의 지연과 처리량에는 측정 오차보다 작은 영향만 준다. 비용은 요청 하나가 아니라 시계열 수에서 나온다. 그래서 제안 1(HTTP 히스토그램)을 어떤 방식으로 켜느냐가 거의 전부다.

### 1. 요청 하나에 드는 비용
| 제안 | 요청마다 하는 일 | 비용 |
|---|---|---|
| 1. HTTP 히스토그램(`slo` 버킷) | 이미 기록하는 `http.server.requests` 타이머가 버킷 배열에서 자리를 찾아 원자 카운터 하나를 더 올린다 | 나노초 단위 |
| 2. `seathold.result` 카운터 | `LongAdder` 증가 한 번 | 나노초 단위 |
| 3. 좌석 조회 Timer | 시각 두 번 읽기와 기록 | 나노초~마이크로초 단위 |

선점 요청은 DB 트랜잭션, 좌석 행 잠금, Redis 호출로 밀리초 단위다. 위 비용은 그보다 세 자릿수 이상 작다. 커넥션 풀이나 잠금을 쥐는 시간도 바뀌지 않는다.

### 2. 실제로 비용이 생기는 곳: 시계열 수
`http.server.requests`는 `method`, `uri`(경로 템플릿), `status`, `outcome`, `exception`, `application` 태그 조합마다 시계열을 만든다. 히스토그램을 켜면 조합마다 버킷 수만큼 `_bucket` 시계열이 더 생긴다.

| 방식 | 조합마다 늘어나는 시계열 | 조합이 약 100개일 때(추정) |
|---|---|---|
| `slo` 버킷 6개 | 7개(버킷 6 + `+Inf`) | 약 700개 |
| `percentiles-histogram: true` | 기본 버킷 약 70개 | 약 7,000개 |
| `percentiles`(클라이언트 계산) | 분위수마다 1개, 대신 앱 메모리에 구간 창을 둔다 | 적지만 인스턴스 사이에서 합칠 수 없다 |

조합 수 100개는 reservation-service의 매핑 37개와 상태 코드 몇 가지로 어림한 값이고 재지 않았다.

이 수가 영향을 주는 곳은 세 군데다.
- **앱의 스크레이프 응답:** Prometheus가 5초마다 `/actuator/prometheus`를 긁을 때 앱이 텍스트를 만든다(`infra/prometheus.yml`). 시계열이 수천 개 늘면 그 순간 CPU와 할당이 조금 는다. 관리 포트(9080)라 요청 스레드와 따로 돌지만 같은 JVM의 CPU·GC는 나눠 쓴다.
- **Prometheus 메모리와 디스크:** 배포 서버에서 Prometheus가 앱과 같은 호스트에서 돈다(`compose.deploy.yml`). 시계열이 크게 늘면 측정 중에 호스트 자원을 나눠 쓴다.
- **대시보드 쿼리:** `histogram_quantile`이 버킷 수에 비례해 무거워진다.

### 3. 지켜야 할 것
- `percentiles-histogram` 대신 `slo` 버킷 몇 개를 고른다. 선점 SLO 근처(예: 50·100·200·300·500ms·1s)만 두면 p95가 어느 구간인지 충분히 보인다.
- 범위를 `http.server.requests` 전체가 아니라 필요한 URI로 줄이려면 `MeterFilter`가 필요하다. 설정 한 줄로는 서비스 단위까지만 나눌 수 있다.
- 카운터 라벨에 사용자·좌석·회차 ID를 넣지 않는다. 이슈 본문의 제안대로 `outcome` 여섯 값이면 시계열 6개다.
- 카운터는 기동할 때 만들어 필드에 둔다. 요청마다 `Counter.builder(...).register()`를 부르면 태그 정렬과 레지스트리 조회가 매번 일어난다. `LotteryMetrics`처럼 하면 된다.
- 기록은 트랜잭션 밖(응답 직전, 예외 처리기)에서 해도 되고 안에서 해도 비용은 같다. 다만 롤백된 시도를 성공으로 세지 않도록 결과가 정해진 뒤에 센다.

### 4. 확인 방법
- 변경 전후 같은 k6 시나리오에서 선점 p95와 처리량을 비교한다. 차이가 측정마다 생기는 흔들림 안에 있으면 영향이 없다고 본다.
- Prometheus 자체 지표로 비용을 직접 본다: `scrape_samples_scraped{job="reservation-service"}`(시계열 수), `scrape_duration_seconds`(스크레이프 시간).
- 지금까지 위 수치는 코드와 설정을 읽고 추정한 것이고 재지 않았다.

## 관련
- Linear ISE-277, 정리 노트 `docs/learning/kangmin/qna/2026-11-13-domain-metrics-gap-seathold-queue-concert.md`
- 기존 도메인 지표 예: `reservation-service/.../lottery/observability/LotteryMetrics.java`
