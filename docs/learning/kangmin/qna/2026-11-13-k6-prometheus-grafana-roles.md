---
title: k6, Prometheus, Grafana는 각각 무슨 일을 하고 이 저장소에서는 어떻게 이어져 있나
date: 2026-11-13
updated: 2026-11-13
asker: kangmin
level: 입문
tags: [k6, prometheus, grafana, observability, loadtest, remote-write, pull-model]
---

# k6, Prometheus, Grafana는 각각 무슨 일을 하고 이 저장소에서는 어떻게 이어져 있나

## 질문
k6, Prometheus, Grafana는 각각 어떤 역할인가?

## 짧은 답
- **k6**: 부하를 **만드는** 도구. 가상 사용자로 요청을 보내고 클라이언트 쪽에서 잰 응답 시간·실패율을 남긴다.
- **Prometheus**: 숫자를 **모으고 저장하는** 시계열 DB. 서버들의 `/actuator/prometheus`를 5초마다 긁어 오고(pull), 알림 규칙을 평가한다.
- **Grafana**: 저장된 숫자를 **보여 주는** 화면. 스스로 데이터를 모으지 않고 Prometheus(그리고 Loki·Zipkin)에 질의해 그래프로 그린다.

한 줄로: k6가 때리고, Prometheus가 기록하고, Grafana가 보여 준다.

## 자세히

### 1. 셋이 나눠 맡는 것
| 도구 | 하는 일 | 하지 않는 일 | 이 저장소 위치 |
|---|---|---|---|
| k6 | 시나리오대로 요청 발생, 클라이언트 쪽 지연(p95·p99)·실패율·처리량 측정, 기준 통과 판정(thresholds) | 서버 안쪽(CPU, 커넥션 풀, Kafka 지연) 관측 | `loadtest/k6/` (k6 EC2에서 실행) |
| Prometheus | 지표 수집(pull), 시계열 저장, PromQL 질의, 알림 규칙 평가 → Alertmanager로 전달 | 그래프 화면, 로그·트레이스 저장 | `infra/prometheus.yml`, `infra/prometheus/rules/` |
| Grafana | 대시보드, 여러 데이터 소스를 한 화면에 묶기, 지표 → 로그·트레이스 이동 | 데이터 수집·저장 | `infra/grafana/dashboards/`, `provisioning/` |

### 2. 데이터가 흐르는 길
```
[k6 EC2] ──요청──▶ Gateway ─▶ 각 서비스
   │                              │ /actuator/prometheus (Micrometer)
   │ remote write(push, 선택)      ▼
   └────────────────────────▶ [Prometheus] ◀── 5초마다 긁어 옴(pull)
                                   │  알림 규칙 → Alertmanager → Slack
                                   ▼
                              [Grafana] ◀── Loki(로그), Zipkin(트레이스)도 질의
```
- 서버 지표는 **pull**이다. `prometheus.yml`의 `spring` 잡이 gateway·user·reservation·payment·eureka·config-server·mock-pg·assistant의 `:9080/actuator/prometheus`를 `scrape_interval: 5s`로 가져간다.
- k6 결과는 **push**다. `PROM_RW_URL`을 주면 `run.sh`가 `K6_PROMETHEUS_RW_SERVER_URL`을 넘겨 k6가 끝날 때까지 `k6_*` 지표를 Prometheus에 밀어 넣는다(`testid` 태그 붙음). 받는 Prometheus는 `--web.enable-remote-write-receiver`로 떠 있어야 하고, 측정 모드(`loadtest/deploy/compose.measure.yml`)가 그 옵션을 켠다. 주소를 안 주면 k6 요약은 파일·터미널에만 남는다.

### 3. 왜 이렇게 나누나 (선택지 비교)
| 선택지 | 장점 | 단점 |
|---|---|---|
| k6 요약만 본다 | 설치 없음 | "느려졌다"는 알지만 **왜**인지(DB CPU, 풀 고갈, GC) 모른다 |
| 서버 지표만 본다 | 원인 쪽은 보인다 | 사용자가 겪은 지연(네트워크·큐잉 포함)을 모르고, 부하 크기와 시점을 맞추기 어렵다 |
| **k6 결과도 Prometheus에 넣고 Grafana 한 화면에** (이 저장소) | 같은 시간축에서 "k6 p95가 튄 순간 = Hikari 대기 증가" 같은 원인 맞추기가 된다 | 측정 모드 compose, remote write 설정, Prometheus 메모리(768m 상한) 부담 |

pull을 서버 지표의 기본으로 쓴 이유는 Prometheus 쪽이 대상 목록과 주기를 쥐고 있어 "대상이 죽었는지(`up == 0`)"까지 알 수 있어서다. k6만 push인 이유는 k6가 짧게 살다 사라지는 프로세스라 긁어 갈 틈이 없기 때문이다.

### 4. 옆에 붙은 도구 (같이 헷갈리기 쉬움)
| 도구 | 역할 |
|---|---|
| Alertmanager | Prometheus 규칙이 울린 알림을 묶고 Slack으로 보냄 |
| Loki | 로그 저장소(Grafana가 질의) |
| Alloy | 컨테이너 로그를 모아 Loki로 보내는 수집기 |
| Zipkin | 분산 트레이스 저장소. 대시보드·시연 모드의 traceId 링크가 여기로 이어진다 |

### 5. 감수한 점
- Prometheus는 메모리 시계열이라 고카디널리티 라벨(사용자 ID, URL 경로 원문)을 넣으면 메모리가 바로 찬다. 라벨은 묶인 값으로만 둔다.
- 5초 수집 간격보다 짧은 순간 몰림(예: 오픈 첫 0.2초)은 서버 지표로 안 보인다. 그 구간은 k6 쪽 측정과 로그로 본다.
- k6 remote write의 추세 통계는 기본 `p(95),p(99),avg,max`만 보낸다. 다른 백분위가 필요하면 `K6_PROMETHEUS_RW_TREND_STATS`로 바꿔야 한다.

### 6. 실제 사례: remote write 카디널리티로 Prometheus OOM (2026-11-13 저녁)
- O1(오픈 피크 한 흐름)을 `PROM_RW_URL`로 돌렸더니 배포 Prometheus가 메모리 상한 768MiB에서 OOM으로 재시작했다(19:20 KST).
- 원인: k6는 요청마다 `url`과 `name`(기본값이 URL 전체) 태그를 붙인다. O1의 확정 조회 URL `/api/v1/reservations/{예매 id}`가 예매마다 달라, `k6_http_req_duration_p99` 하나에만 시계열 23,647개 중 23,509개가 이 경로였다(서로 다른 URL 14,593개). k6 지표 이름 102개를 곱하면 전체 시계열은 713,909개였다.
- 위 5절 "고카디널리티 라벨" 주의가 k6 쪽에서 그대로 일어난 것이다. 서비스 지표는 Spring이 `uri`를 경로 틀(`/api/v1/reservations/{id}`)로 묶어 주지만, k6는 묶지 않는다.
- 막는 법: (1) remote write를 끄고 CSV로 계산한다. (2) 요청에 `tags: { name: '<경로 이름>' }`을 주고 k6 `systemTags`에서 `url`을 뺀다(이 방법은 시험하지 않았다).
- 자세한 수치: `docs/context/safety-net-measure-2026-11-13.md` 5·5-1절(git 제외)

### 7. k6 remote write를 끄면 분석에 불이익이 있나 (2026-11-13 저녁 질문)
- 꺼지는 것은 Grafana의 **k6 쪽 지표(`k6_*`)뿐**이다. 서버 지표(Gateway 처리 중 수·힙, 서비스 CPU, Hikari 등)는 Prometheus가 계속 수집(scrape)하므로 Grafana에 그대로 나온다.
- 분석 숫자는 잃지 않는다. 발생기의 raw CSV에 요청 한 건마다 시각·태그·상태·응답 시간이 남는다. remote write는 p95·p99·avg·max 같은 요약만 보내므로 CSV가 더 자세하다(경로별 p95, 10초 구간 처리율, 실패 code별 수를 이번에 모두 CSV로 냈다).
- 잃는 것
  - 측정 **도중** k6 응답 시간·실패를 화면으로 보지 못한다. 이상을 일찍 보고 멈추는 판단은 서버 지표와 k6 출력 끝부분으로 대신한다.
  - k6 그래프와 서버 그래프를 같은 시간축에 겹친 그림을 Grafana에서 바로 못 만든다. 시간 맞추기는 CSV의 epoch 시각과 서버 수집 파일 시각으로 스크립트가 한다. 발표용 그림은 따로 그려야 한다.
- 정리: 측정 판정에는 불이익이 거의 없고, 실시간 관찰과 발표용 화면에서 불편이 생긴다.

## 확인할 질문
- 측정 때 Grafana 대시보드 중 k6 지표(`k6_*`)를 그리는 패널이 있는가, 아니면 Explore에서 직접 질의하는가? (이번에 대시보드 JSON은 열어 보지 않았다.)
- 배포 Prometheus의 보존 기간(retention)은 몇 일인가? 측정 결과를 나중에 다시 보려면 알아야 한다. (확인 못 함)

## 근거
- `infra/prometheus.yml` (scrape 대상, 5초 간격, Alertmanager, 규칙 경로)
- `loadtest/k6/run.sh` 38~45행, `loadtest/k6/README.md` `PROM_RW_URL` 항목
- `compose.deploy.yml` prometheus·grafana·loki·alloy·zipkin 항목
- `infra/grafana/provisioning/datasources/` (prometheus.yml, loki.yml, zipkin.yml)
