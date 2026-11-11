---
title: 서비스 구성과 예매 흐름
date: 2026-11-11
updated: 2026-11-11
asker: KWON-WOO
level: 중급
tags: [msa, gateway, kafka, saga, state-machine]
---

# 서비스 구성과 예매 흐름

목차: [README](README.md)

## 1. 큰 그림

> 비유: 건물 하나에 부서가 여러 개 있는 회사다. 손님은 1층 보안 데스크(Gateway)만 통과하면 되고, 부서끼리는 전화(HTTP)보다 사내 우편(Kafka)을 주로 쓴다. 부서마다 자기 서류함(DB)을 따로 가진다.

```
브라우저 ─▶ frontend(nginx :80) ─▶ gateway(:8080) ─┬─▶ user-service(:8081)        ─ postgres-user, Redis
                                                   ├─▶ reservation-service(:8082) ─ postgres-reservation, Redis
                                                   ├─▶ payment-service(:8083)     ─ postgres-payment ─▶ mock-pg(:8090) / Toss
                                                   └─▶ assistant-service(:8095)   ─ Claude API
          reservation ◀── Kafka(payment.result / payment.command) ──▶ payment
          모든 앱 ─▶ config-server(:8888) 설정, eureka(:8761) 등록
```

## 2. 서비스별 역할

| 모듈 | 하는 일 |
|---|---|
| gateway | Spring Cloud Gateway(WebFlux). JWT 검증, 로그아웃 블랙리스트 확인(Redis, 서킷 브레이커 `auth-redis`), `X-User-Id`·`X-User-Role` 전달, 경로를 PUBLIC/USER/ADMIN/INTERNAL로 판정, `/internal/**`은 외부에 404, 좌석 조회·선점에서 입장 토큰 검증 |
| eureka | 서비스 레지스트리 |
| config-server | `config-repo/`를 읽어 설정을 나눠 준다 |
| user-service | 회원가입, 로그인, Refresh 토큰 회전(Redis Lua), 로그아웃 블랙리스트, 관리자 임시 비밀번호 발급 |
| reservation-service | 모듈러 모놀리스. 안에 concert(공연·회차·좌석), queue(대기열), seathold(선점·만료), reservation(결제 결과 반영·티켓), lottery(추첨)가 있다 |
| payment-service | 결제 승인, 빌링키 자동결제, 환불 워커, 재확인 스케줄러, Outbox, DLQ, 대사, Ops 명령행 |
| mock-pg | 모의 PG. 승인·조회·망취소·환불·빌링키. 장애 주입은 `test`·`loadtest` 프로필에서만 열린다 |
| assistant-service | AI 상담. DB·Kafka 없이 Claude API만 쓰고, 키가 없거나 실패하면 문서 검색 모드로 답한다 |
| frontend | React 19 + Vite. nginx가 정적 파일을 내주고 `/api/`를 gateway로 넘긴다 |
| common | 응답 봉투, ErrorCode, 토큰 디코더, Outbox·Inbox·컨슈머 오류 처리기, 이벤트 봉투 |
| infra | Prometheus, Alertmanager(슬랙), Grafana, Loki, Alloy, Jenkins 설정 |

`gateway-edge`, `eureka-registry` 폴더는 초기 브랜치 통합 때 남은 빌드 잔재다. 소스가 없고 `settings.gradle`에도 없다.

### 왜 reservation-service 하나에 다섯 도메인을 넣었나

좌석 선점, 예매 확정, 추첨 당첨 선점은 같은 좌석 행을 한 트랜잭션에서 바꿔야 한다. 서비스를 나누면 이 작업이 분산 트랜잭션이 된다. 그래서 서비스는 하나로 두고 패키지(모듈)로 경계를 나눴다. 이것을 모듈러 모놀리스라고 한다.

> 비유: 주방, 홀, 계산대를 다른 건물로 나누면 음식을 나를 때마다 길을 건너야 한다. 한 건물 안에서 칸막이만 친 셈이다.

## 3. 인프라

| 구성 | 값 | 이유 |
|---|---|---|
| PostgreSQL 17 × 3 | user / reservation / payment | 서비스마다 DB 분리(Database per Service) |
| Redis 8 | AOF 켜짐, noeviction | 대기열과 인증. 메모리가 차도 키를 몰래 지우지 않게 noeviction |
| Kafka 4.1.1 | KRaft 단일 노드 | ZooKeeper 없이 동작 |
| 관측 | Prometheus, Grafana, Loki, Alloy, Zipkin, Alertmanager | 지표, 로그, 추적, 슬랙 알림 |
| 배포 | `compose.deploy.yml`, 이미지 `ghcr.io/iseonjwa/*:<커밋 SHA>`, Jenkins | EC2 한 대에 docker-compose |

Spring Boot 4.1.2를 쓴다(`build.gradle`).

## 4. 예매 한 건의 흐름 (일반 예매)

> 비유: 놀이공원. 번호표 받기 → 전광판에 내 번호가 뜨면 탑승권 받기 → 자리에 가방 올리기 → 매표소에서 결제 → 표 받기.

1. 로그인 `POST /api/v1/auth/login`: Access·Refresh 토큰을 받는다. 이후 요청마다 Gateway가 JWT와 블랙리스트를 확인한다.
2. 대기열 진입 `POST /schedules/{id}/queue`: Redis Lua(`enter.lua`)가 순번(seq)을 준다.
3. 순번 조회 `GET /schedules/{id}/queue/me`: 3~10초 간격(jitter 포함)으로 폴링한다. `QueueAdmissionScheduler`가 500ms마다 돌며 `admitted` 값을 초당 50명씩 올린다(`config-repo/reservation-service.yml`의 `admit-per-second: 50`). 내 seq가 admitted 이하면 READY다.
4. 입장 토큰 발급 `POST /schedules/{id}/queue/token`: HS256, 10분짜리 토큰. claim에 회차 ID가 들어 있다.
5. 좌석 조회 `GET /schedules/{id}/seats`: Gateway가 입장 토큰의 서명·만료·사용자·회차를 검사한다.
6. 좌석 선점 `POST /schedules/{id}/reservations`(Idempotency-Key 필요): `SyncSeatHoldService`가 한 트랜잭션에서 처리한다.
   - 1인 매수 한도(최대 4매) 조건부 UPDATE
   - 좌석 `AVAILABLE → HELD` 조건부 UPDATE(좌석 ID 오름차순)
   - 예매·예매 좌석 HELD 저장, 요청 이력 저장
   - 201과 함께 결제 토큰을 준다. 선점은 5분, 결제 토큰은 선점 만료 10초 전에 끝난다.
7. 결제 `POST /api/v1/payments`(Idempotency-Key 필요):
   - 멱등 기록 조회 → 결제 토큰·금액 검증 → PG 호출 허가(Bulkhead·서킷 브레이커)
   - TX1: 결제 REQUESTED 저장 후 커밋
   - 트랜잭션 밖에서 PG 호출
   - TX2: 결제 상태와 Outbox(`payment.result`)를 함께 저장
   - 응답: 200(APPROVED/DECLINED), PG 타임아웃이면 202(UNKNOWN)
8. 예매 확정: Outbox 릴레이가 Kafka로 보내고 reservation의 `PaymentResultListener`가 받는다.
   - 승인 + HELD + 승인 시각이 선점 만료 전: CONFIRMED, 좌석 SOLD, 좌석별 티켓 ISSUED
   - 거절: RELEASED, 좌석과 매수 반환
   - 이상 상황(만료 뒤 승인, 중복 승인, 금액 불일치): 자동 환불 요청 → [03 장애 대응](03-resilience.md) 1절
9. 결제하지 않으면 `SeatHoldExpiryScheduler`가 5초마다 만료 선점을 풀어 좌석을 AVAILABLE로 돌린다.

### 왜 PG 호출을 트랜잭션 밖에서 하나

PG 응답은 최대 2초가 걸린다. 트랜잭션 안에서 기다리면 그동안 DB 커넥션을 쥐고 있어 풀(기본 5개)이 금방 마른다. 그래서 "기록(TX1) → 외부 호출 → 반영(TX2)"으로 나눴다. 대신 TX1과 TX2 사이에 서버가 죽으면 REQUESTED가 남는데, 재확인 스케줄러가 이것을 정리한다.

## 5. 추첨 자동결제 흐름

> 비유: 인기 공연 일부 좌석은 선착순 대신 경품 추첨으로 판다. 응모할 때 카드를 미리 등록해 두면 당첨 즉시 자동으로 결제된다.

1. 관리자가 회차에 추첨을 설정한다. 일부 좌석이 등급 `LOTTERY`로 빠지고, 일반 선점은 이 좌석을 잡지 않는다.
2. 사용자가 빌링키를 등록한다(`POST /api/v1/payments/billing-keys`).
3. 응모 `POST /lotteries/{id}/entries`: 동기 INSERT로 201을 준다.
4. `LotteryDrawScheduler`가 마감하고 추첨한다. 시드 기반 SHA-256 순위로 WON, WAITLISTED, LOST를 정한다. 시드가 같으면 결과가 같아 재현·검증할 수 있다.
5. `LotteryAssignmentScheduler`(1초)가 당첨자 1명씩 시스템 선점으로 HELD 예매를 만들고, Outbox로 `payment.command`에 `BillingPaymentRequested`를 쓴다.
6. payment의 `BillingPaymentService`가 빌링키로 결제하고 결과를 `payment.result`로 낸다.
7. reservation이 예매를 확정하고 응모를 PAID 또는 FAILED로 바꾼다.
8. 실패하면 `LotterySuccessionScheduler`가 예비자를 승계시킨다. 승계 마감은 추첨 후 30분이다.
9. `LotteryFinishScheduler`가 추첨을 DONE으로 바꾸고 남은 추첨석을 원래 등급으로 돌린다.

설계(SA)와 다른 점: SA는 응모를 Kafka `lottery.entry`로 받아 202를 주게 되어 있지만 코드는 동기 201이다.

## 6. Kafka 토픽

| 토픽 | 이벤트 | 발행 → 구독 |
|---|---|---|
| `payment.result` (키 reservationId) | PaymentApproved, PaymentDeclined, PaymentRefunded | payment → reservation |
| `payment.command` (키 reservationId) | RefundRequested, BillingPaymentRequested | reservation → payment |
| `payment.result.dlq`, `payment.command.dlq` | 처리 실패 메시지 | 각 서비스의 DLQ 처리기 |
| `seat-hold.request`, `lottery.entry` | 접수형 선점, 응모 | 설계만 |

키를 reservationId로 잡아 같은 예매의 이벤트는 같은 파티션에 들어가 순서가 지켜진다. 이벤트 봉투는 `eventId, eventType, version, occurredAt, producer, traceId, payload`다(`common/.../event/EventEnvelope.java`).

### 왜 결제 결과를 HTTP 호출이 아니라 Kafka로 넘기나

payment가 reservation을 직접 부르면 reservation이 잠깐 죽어 있을 때 결제 결과가 사라진다. 우편함(Kafka)에 넣어 두면 reservation이 살아난 뒤 꺼내 처리한다. 두 서비스가 서로의 가동 상태에 묶이지 않는다. 조정자 없이 이벤트로 이어 가는 이 방식을 Choreography SAGA라고 한다.

## 7. 상태 머신

모든 전이는 `UPDATE ... WHERE status = '이전 상태'` 조건부 UPDATE다. 영향 행이 0이면 다른 쪽이 먼저 바꾼 것이므로 후속 쓰기를 하지 않는다.

> 비유: 신호등. 빨간불에서만 초록불로 바뀔 수 있다. 두 사람이 동시에 스위치를 눌러도 한 명만 바꾼다.

| 대상 | 상태 | 주요 전이 |
|---|---|---|
| 예매 | HELD, CONFIRMED, EXPIRED, RELEASED, CANCELED | HELD→CONFIRMED(승인), HELD→EXPIRED(만료), HELD→RELEASED(거절). CONFIRMED→CANCELED는 설계만 |
| 회차 좌석 | AVAILABLE, HELD, SOLD | AVAILABLE→HELD→SOLD, HELD→AVAILABLE |
| 결제 | REQUESTED, UNKNOWN, APPROVED, DECLINED, NEEDS_MANUAL_CHECK, REFUNDED | REQUESTED→APPROVED/DECLINED/UNKNOWN, UNKNOWN→재조회→확정 또는 NEEDS_MANUAL_CHECK, APPROVED→REFUNDED |
| 환불 | REQUESTED, UNKNOWN, REFUNDED, NEEDS_MANUAL_CHECK, FAILED | FAILED는 운영자만 쓰는 종결 상태 |
| 추첨 | OPEN → CLOSED → DRAWN → DONE | |
| 응모 | ENTERED, CANCELED, WON, WAITLISTED, LOST, PAID, FAILED | WAITLISTED→WON(승계), WON→PAID/FAILED |
| 대기열 | READY, WAITING, ADMITTED | |

## 면접 포인트

- "서비스를 어떤 기준으로 나눴나" → 데이터를 함께 바꿔야 하는 범위로 나눴다. 좌석·예매·추첨은 한 트랜잭션이 필요해 한 서비스에 두고, 결제는 외부 PG와 돈을 다뤄 분리했다.
- "동시에 같은 좌석을 잡으면" → 조건부 UPDATE에서 한 명만 1행을 얻는다. 좌석 ID 오름차순으로 잠가 교착(데드락)을 줄인다.

## 참고

- `docs/core/SA MASTER.md` 4·7·8·10장
- `docs/design/reservation/contracts.md`, `docs/design/queue/contracts.md`, `docs/design/lottery/`
- `reservation-service/src/main/resources/redis/queue/*.lua`
