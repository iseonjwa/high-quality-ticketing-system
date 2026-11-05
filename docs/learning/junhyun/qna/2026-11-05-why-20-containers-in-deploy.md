---
title: 배포 서버에 컨테이너가 왜 20개나 뜨나
date: 2026-11-05
updated: 2026-11-05
asker: Junhyun An
level: 기초
tags: [docker-compose, msa, deployment, observability]
---

# 배포 서버에 컨테이너가 왜 20개나 뜨나

## 질문
배포 서버에 컨테이너가 20개나 뜨는 이유는 무엇인가.

## 짧은 답
비즈니스 서비스는 3개(user, reservation, payment)뿐이다. 나머지 17개는 그 3개를 받치는 컨테이너다.

| 묶음 | 수 | 무엇 | 상한 합계 |
|---|---|---|---|
| 앱 | 8 | 비즈니스 서비스 3, MSA 인프라 3(gateway, eureka, config-server), 가짜 PG(mock-pg), 화면(frontend nginx) | 약 4.8GB |
| 데이터 | 6 | 서비스별 PostgreSQL 3, Redis, Kafka, Kafka-UI | 약 4.5GB |
| 관측 | 6 | Prometheus, Grafana, Alertmanager, Loki, Alloy, Zipkin | 약 2.8GB |

- compose에 정의된 서비스는 21개다. 그중 `kafka-init`은 Kafka 볼륨 소유자만 바꾸고(`chown`) 끝나는 일회용 컨테이너다. 그래서 계속 떠 있는 것은 20개다.
- 상한은 다 합쳐 약 12.1GB다. 실제로는 배포 직후 유휴 상태에서 약 4.7GB만 썼다.

## 자세히

### 1. 요청 하나가 지나가는 길로 보기
```
브라우저
  → frontend(nginx :80)  화면 파일을 주고, /api/ 요청은 gateway로 넘긴다
  → gateway              인증·입장 토큰 확인, 어느 서비스로 보낼지 결정
      (eureka에서 서비스 위치를 찾는다)
  → user / reservation / payment   비즈니스 로직
      → 각자의 PostgreSQL            서비스마다 DB가 따로다
      → Redis                        대기열, 로그인 세션 등
      → Kafka                        예매 ↔ 결제 이벤트(SAGA)
      → mock-pg                      payment가 부르는 가짜 결제 대행사
```
- 앱이 뜰 때는 config-server에서 설정(`config-repo`)을 받아 간다.
- 외부에 열린 것은 frontend의 80번 포트 하나다. 나머지는 서버 안 네트워크에서만 서로 부른다.

### 2. 묶음별로 왜 필요한가

**앱 8개 (`ghcr.io/iseonjwa/*` 이미지)**
| 컨테이너 | 하는 일 | 왜 따로 있나 |
|---|---|---|
| user-service | 회원·로그인 | 서비스 경계(ADR)대로 나눈 비즈니스 서비스 |
| reservation-service | 공연·대기열·좌석·예매·추첨 | 같음 |
| payment-service | 결제·환불·자동결제 | 같음 |
| gateway | 단일 진입점, 인증 필터 | 클라이언트가 서비스 주소를 몰라도 되게 한다 |
| eureka | 서비스 디스커버리 | 서비스 주소를 이름으로 찾는다 |
| config-server | 설정 중앙화 | 설정을 저장소 `config-repo` 한 곳에서 관리한다 |
| mock-pg | 가짜 PG | 실제 PG에는 부하를 줄 수 없고 장애 주입도 못 한다 |
| frontend | nginx로 React 화면 제공, `/api/` 프록시 | 공개 입구(80) |

**데이터 6개**
| 컨테이너 | 왜 |
|---|---|
| postgres-user, postgres-reservation, postgres-payment | 서비스마다 DB를 따로 둔다(database per service). 한 서비스가 다른 서비스의 테이블을 직접 읽지 못하게 경계를 지킨다 |
| redis | 대기열(Lua 스크립트), 로그인 세션, 게이트웨이의 입장 토큰 확인 |
| kafka | 서비스 사이 비동기 이벤트(예매 ↔ 결제 SAGA, DLQ) |
| kafka-ui | Kafka 토픽·컨슈머 상태를 보는 화면(배포에서는 읽기 전용) |

**관측 6개**
관측(observability)은 보통 지표(metrics), 로그(logs), 트레이스(traces) 세 가지로 나눈다.

| 컨테이너 | 맡은 것 |
|---|---|
| prometheus | 지표: 앱의 `/actuator/prometheus`를 주기적으로 긁어 저장한다 |
| alertmanager | 알림: Prometheus 경보를 슬랙 `#이선좌-성능-모니터링`으로 보낸다 |
| alloy | 로그 수집: 컨테이너 로그를 모아 Loki로 보낸다 |
| loki | 로그 저장·검색 |
| zipkin | 트레이스: 요청 하나가 서비스 여러 개를 지나간 경로와 시간 |
| grafana | 위 지표·로그·트레이스를 한 화면(대시보드)으로 본다 |

### 3. 많아 보이는 이유와 줄이는 방법
- **한 서버에 다 띄웠다.** 운영 환경이라면 DB·Redis·Kafka는 매니지드 서비스(RDS, ElastiCache, MSK)로 빼고, 관측도 공용 스택이나 매니지드 서비스로 뺀다. 그러면 앱 서버에는 앱 8개만 남는다. 이 프로젝트는 리허설 일정 때문에 EC2 한 대로 시작했고, 분리는 11/7~11/12에 검토한다(ADR 0047).
- **MSA라서 늘어난 몫이 있다.** gateway·eureka·config-server와 서비스별 DB 3개가 그렇다. 모놀리스였다면 앱 1개, DB 1개였을 것이다. 대신 서비스를 따로 배포하고 따로 확장할 수 있고, 장애가 한 서비스 안에 머문다.
- **줄여야 한다면** 8GB급 서버일 때 SA 9-3이 든 대안처럼 Zipkin·Kafka-UI를 빼거나 상한을 줄인다. 지금은 16GB급이고 유휴 사용량이 약 4.7GB라 줄일 필요가 없다.

### 4. 확인 근거
- 목록: `IMAGE_TAG=x docker compose -f compose.yml -f compose.deploy.yml config`로 합친 설정의 서비스 21개와 각 `mem_limit`(배포 스크립트 `script/deploy/deploy.sh`도 같은 두 파일을 쓴다).
- 상한 합계와 첫 배포의 컨테이너 20개, 유휴 4.7GB: `docs/design/infra/deployment.md` 5절과 첫 배포 기록.
- Redis 사용처: `reservation-service`의 `RedisQueueRepository`, `user-service`의 `RedisSessionStore`, `gateway`의 `EntryTokenFilter`.

## 면접 포인트
- "컨테이너 20개"는 서비스가 20개라는 뜻이 아니다. 비즈니스 서비스 3개와 그걸 받치는 인프라(진입점, 디스커버리, 설정), 서비스별 데이터 저장소, 관측 스택으로 나눠 설명한다.
- database per service: 서비스마다 DB를 나누면 경계와 독립 배포를 얻는다. 대신 서비스를 걸친 트랜잭션은 SAGA(이 프로젝트는 Kafka 이벤트)로 푼다.
- 관측의 세 축은 지표(Prometheus), 로그(Loki), 트레이스(Zipkin)다. 장애가 나면 지표로 알아채고, 트레이스로 어느 서비스인지 찾고, 로그로 원인을 본다.
- 단일 호스트는 리허설용 타협이다. 상태를 가진 구성 요소(DB·캐시·브로커)부터 매니지드로 빼는 것이 다음 단계다.

## 관련 문서
- `docs/design/infra/deployment.md`, `docs/design/infra/observability.md`, `docs/decisions/0047-single-ec2-first-managed-split-review.md`
- [Kafka 기초](2026-11-04-kafka-basics.md), [대기열 진입 멱등](2026-11-04-queue-enter-idempotency-lua.md), [배포 EC2 요금과 한 인스턴스 설계](2026-11-04-deploy-cost-and-single-host-db.md)
