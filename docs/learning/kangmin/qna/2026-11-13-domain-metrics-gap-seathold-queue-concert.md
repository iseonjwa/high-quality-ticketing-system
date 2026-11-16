---
title: 좌석 선점·대기열·공연에는 Prometheus 지표가 없어서 합성 부하로 재도 제대로 된 지표를 못 보는가
date: 2026-11-13
updated: 2026-11-13
asker: kangmin
level: 중급
tags: [prometheus, micrometer, observability, loadtest, histogram, business-metrics, k6]
---

# 좌석 선점·대기열·공연에는 Prometheus 지표가 없어서 합성 부하로 재도 제대로 된 지표를 못 보는가

## 질문
좌석 선점, 대기열, 공연 쪽에는 Prometheus가 적용되어 있지 않다. 그렇다면 합성 데이터(k6 가상 사용자)로 부하를 주고 지표를 하나씩 뽑으려 해도 제대로 확인이 안 되는 것 아닌가?

## 짧은 답
절반은 맞고 절반은 틀리다.
- **틀린 부분**: Prometheus가 "적용 안 됨"은 아니다. reservation-service 전체가 수집 대상이라 세 영역의 HTTP 요청 수·오류 수·평균 지연·최대 지연, 커넥션 풀, JVM 지표는 이미 나온다.
- **맞는 부분**: 세 영역에는 **도메인 지표가 하나도 없고**, 서버 쪽 **p95·p99를 계산할 히스토그램도 꺼져 있다.** 그래서 "몇 건이 선점 충돌로 졌나", "대기열 길이와 입장 속도", "서버 안에서의 p95" 같은 질문은 Prometheus만으로 답하지 못한다.
- 다만 이 빈칸은 **k6 쪽 측정과 측정 뒤 DB·Redis 직접 집계로 상당 부분 메울 수 있다.** 지표가 없어서 측정이 불가능한 것이 아니라, 원인 추적과 실시간 확인이 약한 것이다.

## 자세히

### 1. 지금 있는 것과 없는 것 (코드로 확인)
| 구분 | 있음 | 근거 |
|---|---|---|
| 수집 대상 | reservation-service `:9080/actuator/prometheus`, 5초 간격 | `infra/prometheus.yml` `spring` 잡 |
| 자동 지표 | `http_server_requests_seconds_count/sum/max`(uri·status·method 태그), `hikaricp_*`, `jvm_*` | Spring Boot Actuator 기본, 대시보드가 이미 질의 |
| 직접 만든 지표 | 추첨(`LotteryMetrics`), 결제(`PaymentMetrics`, `DbAggregateGauges`), Gateway 상한·인증 Redis, Outbox | `MeterRegistry`를 쓰는 파일 9개 |
| **좌석 선점·대기열·공연의 직접 지표** | **없음** | `seathold`·`queue`·`concert` 패키지에 `MeterRegistry`·`@Timed`·`Counter` 사용 없음 |
| **HTTP 히스토그램 버킷** | **없음** | `percentiles-histogram`·`slo` 설정이 config-repo에 없음(Boot 기본값은 꺼짐) |
| Redis·PostgreSQL exporter | **없음** | scrape 잡이 `spring` 하나뿐 |
| 알림 규칙 | gateway·lottery·payment만 | `infra/prometheus/rules/` |

### 2. 질문별로 지금 답할 수 있나
| 알고 싶은 것 | Prometheus만으로 | 다른 수단 |
|---|---|---|
| 좌석 선점 API 초당 처리량·5xx 비율 | 됨 (uri 태그로 경로별) | k6 |
| 좌석 선점 서버 쪽 p95 | **안 됨** (count·sum으로 평균, max만) | k6 p95(네트워크·Gateway 포함) |
| 선점 성공 대 충돌(이미 선점됨) 비율 | 상태 코드가 다르면 status 태그로 대략 됨. 같은 코드 안의 이유 구분은 안 됨 | k6 `check`·태그로 응답 분류 |
| 이중 판매가 없었나 | **안 됨** | 측정 뒤 DB 집계(지표보다 이쪽이 정확) |
| 대기열 길이, 초당 입장 수, 대기 시간 | **안 됨** | Redis를 측정 중·뒤에 직접 조회, k6에서 입장까지 걸린 시간 측정 |
| 공연·좌석 조회 캐시 적중률 | **안 됨** | 없음 (지표 추가 필요) |
| 느려진 원인이 DB인지 Redis인지 | 커넥션 풀 대기까지만 | DB·Redis 서버 지표는 따로 봐야 함 (exporter 없음) |

### 3. 그래서 이 관점을 어떻게 볼까
- **"측정 결과가 틀린다"는 아니다.** 사용자 체감 지연과 성공률은 k6가 클라이언트 쪽에서 재고, 정합성(이중 판매, 입장 순서)은 지표보다 측정 뒤 DB·Redis 집계가 더 정확하다. 지표는 샘플링·집계 값이라 "정확히 몇 건"을 증명하는 도구가 아니다.
- **"왜 그 숫자가 나왔는지 설명이 약하다"는 맞다.** 예를 들어 선점 p95가 튀었을 때 서버 처리 시간이 늘었는지, Gateway 앞에서 기다렸는지를 나누려면 서버 쪽 히스토그램이 필요하다. 대기열은 길이·입장 속도가 핵심 지표인데 시간축 그래프가 없어 부하 중의 변화를 볼 수 없다.
- **발표·SA 관점에서는 빈칸이 눈에 띈다.** 추첨·결제는 도메인 대시보드와 알림이 있는데 핵심 흐름인 대기열·선점에 없으면 "폭주를 견딘다"를 실시간 화면으로 보여 주기 어렵다.

### 4. 메우는 선택지 (비교)
| 선택지 | 얻는 것 | 대가 |
|---|---|---|
| A. HTTP 히스토그램 켜기(`management.metrics.distribution.percentiles-histogram.http.server.requests=true` 또는 `slo` 버킷 몇 개) | 경로별 서버 p95·p99, 코드 변경 없음 | 시계열 수가 경로×상태×버킷만큼 늘어남. 버킷 지정(slo)이 더 가볍다 |
| B. 도메인 카운터·게이지 몇 개(선점 결과 `outcome=success/conflict/expired`, 대기열 길이·입장 수) | "충돌로 진 비율", "대기열이 줄어드는 속도"를 그래프로 | 코드 수정, 라벨을 묶인 값으로만 둬야 함(사용자·좌석 ID 금지) |
| C. Redis·PostgreSQL exporter 추가 | 원인이 DB인지 Redis인지 구분 | 컨테이너 추가, 16GB EC2 메모리 예산 |
| D. 지금처럼 k6 + 측정 뒤 직접 집계 | 추가 작업 없음 | 실시간 확인 불가, 원인 분석은 사후 추정 |

A는 설정 한 줄이라 비용 대비 효과가 가장 크다. B는 선점·대기열 담당과 맞춰야 하는 변경이라(지표 이름·라벨은 대시보드가 따르는 계약) 팀 확인이 필요하다.

## 확인할 질문
- 좌석 선점의 "이미 선점됨"이 별도 상태 코드로 나가는가? → 확인함(같은 날 추가). `SeatHoldErrorCode`의 409는 `SALE_NOT_OPEN`·`PURCHASE_LIMIT_EXCEEDED`·`SEAT_ALREADY_HELD`·`RESERVATION_NOT_PAYABLE` 네 가지가 같은 409를 쓴다. 그래서 status 태그로는 "업무 거절 전체"만 보이고 좌석 충돌만 따로 세지 못한다. 성공도 동기 선점 201과 접수 202로 갈린다. 공연·좌석 조회 쪽에는 캐시가 없어 캐시 적중률 지표는 대상이 아니다.
- 지난 측정에서 DB CPU는 무엇으로 쟀나(`docker stats` 등)? exporter 없이도 이미 쓰는 경로가 있으면 C의 필요가 줄어든다.
- 지금 대기열·선점에 도메인 지표를 넣는 이슈가 Linear에 있는가? (확인 못 함)

## 근거
- `infra/prometheus.yml` (scrape 잡 `spring` 하나)
- `config-repo/application.yml` `management` 절 (히스토그램 설정 없음)
- `MeterRegistry` 사용 파일 검색 결과: common·gateway·payment·lottery만
- `infra/grafana/dashboards/*.json`, `infra/prometheus/rules/`
