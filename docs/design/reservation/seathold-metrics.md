# 좌석 선점·조회 API 서버 지표 (ISE-277)

부하 측정에서 좌석 선점과 조회 API(회차 좌석, 공연 목록·상세, 접수형 결과 폴링)가 왜 그런 숫자를 냈는지 서버 쪽에서 나눠 보려고 둔 지표다. 대시보드(Grafana `좌석 선점`, `infra/grafana/dashboards/seathold.json`)는 이 문서의 이름과 라벨을 따른다. 2026-11-13 기준이다.

## 1. 지표
| 지표 | 종류 | 라벨 | 어디서 |
|---|---|---|---|
| `http_server_requests_seconds_bucket` | Spring 기본 HTTP 타이머의 히스토그램 | `method`, `uri`, `status`, `outcome`, `exception`, `le` | `config-repo/reservation-service.yml`의 `management.metrics.distribution.slo` |
| `seathold_result_total` | 카운터 | `outcome` | `seathold/observability/SeatHoldMetrics`, 선점 컨트롤러에서 센다 |

### 1-1. HTTP 히스토그램 버킷
- 경계: 25ms, 50ms, 100ms, 200ms, 300ms, 500ms, 1s, 3s. 3s는 Gateway 응답 시한이다.
- reservation-service에만 켠다. 다른 서비스 설정은 바꾸지 않는다.
- `percentiles-histogram`(버킷 약 70개)을 쓰지 않는다. 버킷 하나가 `method`·`uri`·`status` 조합마다 시계열 하나라, 버킷 수가 곧 늘어나는 시계열 수다. 지금은 조합마다 9개(버킷 8 + `+Inf`)가 는다.
- p95·p99는 버킷 경계 사이를 선형으로 어림한 값이다. 경계에 가까운 값만 정확하다. 정확한 비율이 필요하면 `le="0.3"`처럼 경계 하나의 비율을 본다.

### 1-2. `seathold_result_total`의 `outcome`
| 값 | 응답 | 뜻 |
|---|---|---|
| `held` | 201 | 동기형 선점 성공. 같은 멱등키 재요청의 201도 센다 |
| `accepted` | 202 | 접수형 접수. 동기형 서버가 접수형으로 시작한 요청에 답한 202도 센다(ADR 0058) |
| `seat_conflict` | 409 | `SEAT_ALREADY_HELD`. 이미 잡힌 좌석, 잠금 대기 시한 초과 |
| `limit_exceeded` | 409 | `PURCHASE_LIMIT_EXCEEDED` |
| `not_open` | 409 | `SALE_NOT_OPEN` |
| `not_payable` | 409 | `RESERVATION_NOT_PAYABLE`. 같은 멱등키 재요청인데 첫 선점이 닫힘 |
| `entry_token_used` | 403 | `ENTRY_TOKEN_USED`(ADR 0053) |
| `invalid` | 400·404 등 | 그 밖의 업무 거절. 좌석 수 불일치, 회차 없음, 멱등키 재사용 |
| `error` | 500 | 업무 거절이 아닌 예외 |

- 사용자·좌석·회차 ID는 라벨에 넣지 않는다. 시계열은 늘 9개다.
- 기동할 때 모든 값을 0으로 등록해, 아직 한 번도 안 난 결과도 `rate()`가 0으로 보인다.
- 세지 않는 것
  - 컨트롤러 메서드 전에 끝난 400(본문 검증, 헤더 누락): HTTP 지표의 `status="400"`으로 본다.
  - 접수형 소비자가 정한 결과(`HELD`·`REJECTED`): 소비자(ISE-17)가 따로 센다.

## 2. 대시보드 패널
| 패널 | 식(요약) |
|---|---|
| 선점 서버 지연 p50·p95·p99 | `histogram_quantile(q, sum by (le) (rate(http_server_requests_seconds_bucket{uri="/api/v1/schedules/{scheduleId}/reservations",method="POST"}[1m])))` |
| 선점 결과별 초당 응답 | `sum by (outcome) (rate(seathold_result_total[1m]))` |
| 선점 300ms 안 응답 비율 | `le="0.3"` 버킷 / 전체 수 |
| 선점 거절 중 좌석 충돌 비율 | `seat_conflict` / (`held`·`accepted`·`error`를 뺀 결과) |
| 좌석 조회 서버 지연 p50·p95·p99 | 위와 같은 식, `uri="/api/v1/schedules/{scheduleId}/seats"`, `method="GET"` |
| 좌석 조회 상태 코드별 초당 응답 | `sum by (status) (rate(http_server_requests_seconds_count{...}[1m]))` |
| 공연 목록·상세 서버 지연 p95·p99 | 같은 식, `uri="/api/v1/concerts"`와 `uri="/api/v1/concerts/{concertId}"` |
| 공연 목록·상세 상태 코드별 초당 응답 | 같은 식 |
| 접수형 결과 폴링 서버 지연 p50·p95·p99 | 같은 식, `uri="/api/v1/reservations/requests/{requestId}"` |
| 접수형 결과 폴링 상태 코드별 초당 응답 | 같은 식. 선점 결과의 `accepted`와 함께 보면 접수 한 건에 폴링이 몇 번 오는지 보인다 |

히스토그램은 reservation-service의 모든 HTTP API에 걸린다. 패널이 없는 API(내 예매, 대기열 상태, 추첨 조회 등)도 Grafana Explore에서 `uri` 라벨만 바꿔 같은 식으로 본다.

서버 쪽 지연은 요청을 받아 응답을 쓸 때까지다. Gateway와 네트워크 시간은 빠지므로 k6의 지연보다 짧다. 둘의 차이가 크면 Gateway나 커넥션 대기 쪽을 본다.

## 3. 비용
요청마다 드는 비용은 버킷 카운터와 결과 카운터를 올리는 것뿐이라 나노초 단위다. 비용은 시계열 수에서 나온다. 배포 서버에서 Prometheus가 앱과 같은 호스트에서 돌므로, 측정할 때 `scrape_samples_scraped{job="reservation-service"}`와 `scrape_duration_seconds`를 함께 본다. 근거는 [학습 노트](../../learning/kwonwoo/qna/2026-11-13-ise277-metrics-performance-impact.md)다.

## 4. 하지 않은 것
- 좌석 조회 전용 Timer: HTTP 히스토그램이 같은 값을 준다. 조회 안에서 DB 시간만 따로 봐야 할 때 더한다.
- 알림 규칙: 측정으로 기준값을 본 뒤 정한다.
- 대기열 지표: 대기열 담당이 따로 진행한다.
