---
title: Spring Boot Actuator는 정확히 무엇이고 이 프로젝트에서 어떻게 쓰나
date: 2026-11-14
updated: 2026-11-14
asker: GGangMY
level: 입문
tags: [spring-boot, actuator, micrometer, health-check, observability]
---

# Spring Boot Actuator는 정확히 무엇이고 이 프로젝트에서 어떻게 쓰나

## 질문
액츄에이터가 정확히 뭐야.

## 짧은 답
- Actuator는 **운영용 관리 엔드포인트 묶음**이다. 앱의 업무 기능이 아니라 "앱이 살아 있나, 요청 받을 준비가 됐나, 지표가 어떤가"를 바깥(Docker, Prometheus, 운영자)에 HTTP로 알려 준다.
- 의존성 `spring-boot-starter-actuator` 하나를 넣으면 자동 설정으로 `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus` 같은 URL이 생긴다. 컨트롤러를 직접 만들지 않는다.
- 지표를 세는 엔진은 Micrometer이고, Actuator는 그 엔진을 자동으로 켜고(`MeterRegistry` 빈, `http.server.requests` 같은 기본 지표) 결과를 밖에 보여 주는 창구다.

## 자세히

### 1. 역할 나누기
| 구성 요소 | 하는 일 | 비유 |
|---|---|---|
| Micrometer | 숫자를 센다(Counter·Timer·Gauge), `MeterRegistry` | 계기판 안의 센서 |
| `micrometer-registry-prometheus` | 센 숫자를 Prometheus 텍스트 형식으로 바꾼다 | 통역 |
| **Actuator** | Micrometer를 자동 설정하고, 기본 지표(HTTP·JVM·커넥션 풀)를 붙이고, `/actuator/*` URL로 노출한다 | 계기판 + 점검 창구 |
| Prometheus·Grafana | 긁어 가서 저장하고 그린다 | 외부 관제실 |

### 2. 주요 엔드포인트
| 엔드포인트 | 내용 | 이 프로젝트 |
|---|---|---|
| `health` | UP/DOWN. DB·Redis·Kafka 연결 같은 HealthIndicator를 모아 판정 | 노출 |
| `health/liveness`, `health/readiness` | 프로세스가 살아 있나 / 트래픽 받을 준비가 됐나 | `probes.enabled: true`로 켬 |
| `prometheus` | 모든 지표를 Prometheus 형식으로 | 노출 |
| `metrics` | 지표를 JSON으로 하나씩 조회 | 미노출 |
| `env`, `beans`, `configprops`, `threaddump`, `heapdump`, `loggers` | 설정값·빈·스레드·힙 등 내부 정보 | 미노출 |

기본값으로 웹에 노출되는 건 `health`뿐이다. 나머지는 `management.endpoints.web.exposure.include`에 적어야 열린다.

### 3. 이 프로젝트 설정(`config-repo/application.yml` 1~15행)
```yaml
management:
  metrics.tags.application: ${spring.application.name}   # 모든 지표에 서비스 이름 라벨
  server.port: ${MANAGEMENT_PORT:9080}                   # 업무 포트와 분리된 관리 포트
  endpoints.web.exposure.include: health,prometheus      # 이 둘만 연다
  endpoint.health:
    probes.enabled: true                                  # liveness·readiness 경로
    show-details: never                                   # DB 상태 등 세부를 숨김
```

| 설정 | 왜 |
|---|---|
| 관리 포트 9080 분리 | Gateway 라우팅은 업무 포트로만 간다. 관리 URL이 외부 사용자에게 열리지 않고, Prometheus·Docker만 내부망에서 9080을 부른다 |
| `health,prometheus`만 노출 | `env`·`heapdump`는 비밀값과 메모리 내용이 그대로 나온다. 쓰는 것만 연다 |
| `show-details: never` | 어떤 DB·Redis에 붙는지 같은 내부 구조를 응답에 쓰지 않는다 |
| `application` 태그 | 서비스 8개가 같은 `http_server_requests` 이름을 쓰므로 라벨로 구분한다 |

### 4. 누가 부르나
| 호출자 | URL | 위치 |
|---|---|---|
| Docker healthcheck(10초마다) | `http://localhost:9080/actuator/health/readiness` | `compose.yml` 20행 |
| Prometheus(5초마다) | `/actuator/prometheus` | `infra/prometheus.yml` 17행 |

Docker는 readiness가 UP이 될 때까지 컨테이너를 healthy로 보지 않는다. `depends_on: condition: service_healthy`로 기동 순서를 맞출 때 이 값을 쓴다.

### 5. liveness와 readiness 차이
| | liveness | readiness |
|---|---|---|
| 질문 | 프로세스가 고장 나서 재시작해야 하나 | 지금 요청을 보내도 되나 |
| DOWN일 때 하는 일 | 재시작 | 트래픽을 보내지 않고 기다림 |
| 예 | 데드락으로 멈춤 | 기동 중, 웜업 중, 종료 시작 |

readiness에 DB 같은 외부 의존을 넣으면 DB가 잠깐 끊겨도 모든 인스턴스가 동시에 트래픽에서 빠질 수 있어 신중히 넣는다.

## 확인할 질문
- 관리 포트를 업무 포트와 같게 두면 Gateway를 통해 `/actuator/prometheus`가 외부에 보일까
- 커스텀 `HealthIndicator`(예: Kafka 소비자 지연)를 readiness에 넣는 게 맞을까

## 출처
- `config-repo/application.yml`, `compose.yml`, `infra/prometheus.yml`, `build.gradle` 137·139행
- 관련 노트: `qna/2026-11-14-micrometer-prometheus-seathold-metrics.md`
