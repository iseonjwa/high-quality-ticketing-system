---
title: 추첨 자동결제 경로(배정 워커 → outbox → 릴레이 → Kafka → 결제 소비자 → PG 허가 → PG 호출)
date: 2026-11-11
updated: 2026-11-11
asker: kangmin
level: 중급
tags: [lottery, transactional-outbox, kafka, consumer, bulkhead, backpressure, idempotency]
---

# 추첨 자동결제 경로

## 질문
"배정 워커 → reservation outbox(DB) → 릴레이 → Kafka payment.command → 결제 서비스 소비자 → PG 허가 획득 → PG 호출(약 300ms)"을 키워드별로 자세히 설명해 달라.

## 짧은 답
- 예매 서비스는 당첨자마다 예매와 "결제해 달라"는 메시지를 **같은 DB 트랜잭션**에 쓰고(outbox), 별도 스레드가 그 메시지를 Kafka로 보낸다(릴레이).
- 결제 서비스는 Kafka에서 메시지를 **한 번에 하나씩** 꺼내, PG 동시 호출 자리(허가)를 얻은 뒤 PG를 부른다.
- 병목은 소비자 동시 1개 × PG 약 300ms다. 2,000건이면 약 10분(계산값, 미측정).

## 단계별
| 키워드 | 하는 일 | 왜 이렇게 | 대가 | 코드 |
|---|---|---|---|---|
| 배정 워커 | 1초마다 `WON AND reservation_id IS NULL` 응모를 `SKIP LOCKED`로 집어, 당첨자 1명 = 1트랜잭션으로 예매(HELD, 선점 30분)·좌석·응모 연결·outbox 행을 쓴다 | 건별 실패 격리, 서버가 죽어도 다음 주기가 이어받음(D10) | 트랜잭션 2,000번 | `LotteryAssignmentScheduler` |
| outbox (트랜잭셔널 아웃박스) | Kafka로 바로 보내지 않고 보낼 메시지를 DB 테이블에 예매와 같은 트랜잭션으로 저장 | 이중 쓰기 문제(dual write): DB 커밋과 Kafka 전송은 하나로 묶을 수 없어, 사이에서 죽으면 "예매는 있는데 결제 요청이 없음" 또는 그 반대가 생긴다 | 전송이 조금 늦고, 같은 메시지가 두 번 갈 수 있다(최소 한 번 전달) → 받는 쪽 멱등 필요 | `outbox_events` |
| 릴레이 | 단일 스레드가 500ms마다 미발행 행을 20건씩 집어 Kafka로 보내고 발행 표시. 가득 찼으면 한 주기에 최대 10라운드 이어서 비움 | 공통 모듈 하나로 모든 서비스가 같은 방식 사용 | Kafka가 멈추면 행이 쌓인다(F1-b가 이것을 재현) | `OutboxRelayScheduler`, `messaging.outbox.interval`·`batch-size` |
| Kafka `payment.command` | 결제 서비스에게 "해 달라"는 명령 토픽. 자동결제(`BillingPaymentRequested`)와 환불 요청(`RefundRequested`)이 같은 토픽에 온다(D6 기준선). 키가 같은 예매는 같은 파티션에 모여 순서가 지켜진다 | 서비스 간 비동기 연결. 결제 서비스가 잠시 죽어도 메시지가 남는다 | 한 파티션에서 앞 메시지가 막히면 뒤 메시지(환불 포함)도 멈춘다 | `PaymentCommandListener.TOPIC` |
| 결제 서비스 소비자 | 그룹 `payment.command`, 동시성 1, 레코드마다 처리 성공 후 오프셋 커밋. 일반 오류는 3회 재시도 뒤 DLQ(처리 못 한 메시지 보관 토픽) | 키 순서 보장(events-and-reliability.md 7절) | **처리량 상한**: 한 번에 하나라 PG 300ms면 초당 약 3건 | `PaymentCommandListener`, `PaymentCommandKafkaConfiguration` |
| 멱등 | 같은 `reservationId`의 결제가 이미 있으면 새로 만들지 않고 그 결제를 돌려준다. DB `UNIQUE(reservation_id)`가 최후 방어 | outbox·재전송으로 같은 메시지가 두 번 와도 이중 결제가 없게 | — | `BillingPaymentService.approve` 첫 줄, TX1 유니크 충돌 처리 |
| PG 허가 획득 | Resilience4j 벌크헤드 `pg-approve`(동시 10, 대기 0)에서 자리를 얻는다. 일반 결제와 같이 쓴다. 못 얻으면 `PgUnavailableException` → 소비자가 1초 뒤 같은 레코드를 무제한 다시 읽는다(백프레셔) | PG를 과부하에서 보호. 허가 못 얻은 건은 결제 행을 만들기 전이라 다시 해도 안전 | 기다리는 동안 파티션 전체가 멈춘다. 자동결제가 자리를 쓰면 일반 결제 몫이 준다(시나리오 2 문제) | `approvalGate.acquire()`, `Resilience4jPgGroup` |
| PG 호출 | TX1에서 결제 `REQUESTED` 커밋 → 빌링키로 PG 호출(빌링은 늘 Mock PG, ADR 0023) → TX2에서 결과 반영 → `payment.result`로 예매에 알림 | 결과를 모르는 상태(REQUESTED·UNKNOWN)를 DB에 남겨 재조회로 확정(ADR 0018) | TX1과 PG 호출 사이에서 죽으면 `NOT_SENT` 경로(F1-a) | `BillingPaymentService.callPg` |

## 처리 시간
- 300ms는 Mock PG 응답 가정이다. 배포 측정에서 일반 결제 p50 307ms가 나온 기록이 있다(`numbers-and-measurement.md`).
- 소비자 동시 1 → 2,000 × 0.3초 ≈ 10분. 설계 문서의 3.3분은 "자동결제 전용 동시 3" 개선 후보 기준이다.

## 자동결제 전용 자리 분리란 (후속 질문)
D6 B안이다. 요청률 제한(rate limiter)이 아니라 **벌크헤드를 하나 더 두는 것**이다.

| 장치 | 무엇을 제한 | 이 문제에 맞나 |
|---|---|---|
| 벌크헤드(bulkhead) | 동시에 진행 중인 호출 수 | 맞다. 일반 결제 `pg-approve`(동시 10)와 자동결제 `pg-billing`(동시 3)을 따로 두면 자동결제가 아무리 몰려도 일반 결제 자리를 쓰지 못한다 |
| 요청률 제한(rate limiter) | 초당 호출 수 | 자동결제 속도는 늦출 수 있지만 같은 `pg-approve` 자리를 계속 나눠 쓰므로 격리가 아니다. D6의 다른 개선 후보("자동결제 속도 상한")에 해당 |

B안 세부: 전용 토픽 `payment.billing` + 전용 리스너(동시성 3 = 벌크헤드 3)로 환불이 자동결제 뒤에 밀리지 않게 하고, 서킷 브레이커 상태도 그룹별로 둔다. 대가는 PG에 걸리는 동시 수가 최대 13이 되는 것과 결제 쪽 설정·토픽·리스너가 하나씩 느는 것이다.

10/31 결정은 기준선을 A(같이 씀)로 두고, 겹칠 때의 503을 시나리오 2에서 재현해 트러블슈팅 소재로 쓴 뒤 측정값으로 개선안을 고르는 것이었다. 11/10 측정의 503 9.5%와 10분 안 92.9%가 그 "before"다. 10/31 문서의 "겹치지 않으면 2,000건에 약 1분" 가정은 리스너 동시 1을 빠뜨린 값이라, 실측은 약 10분이었다.

## 확인할 질문
- `payment.command` 파티션 수와 결제 서비스 인스턴스 수. 인스턴스를 늘리면 파티션 수까지 소비자가 늘어나는지 확인 못 함.
- 자동결제가 허가를 기다리는 동안 같은 파티션의 환불 요청이 얼마나 늦어지는지 미측정.
