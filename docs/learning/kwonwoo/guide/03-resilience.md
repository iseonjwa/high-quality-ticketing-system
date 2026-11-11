---
title: 장애 대응과 복원력 설계
date: 2026-11-11
updated: 2026-11-11
asker: KWON-WOO
level: 고급
tags: [saga, outbox, idempotency, circuit-breaker, resilience4j, reconciliation]
---

# 장애 대응과 복원력 설계

목차: [README](README.md)

이 프로젝트의 장애 대응은 "돈과 좌석이 어긋나지 않게"라는 한 가지 목표로 모인다. 상태 표기는 구현됨(코드와 테스트 있음), 꺼짐(코드는 있으나 설정으로 끔), 설계만, 측정만(실험 브랜치에서만 적용)이다.

## 한눈에 보기

| 장애 | 막는 장치 | 상태 |
|---|---|---|
| PG 응답이 안 옴 | UNKNOWN + 재확인 스케줄러 + 망취소 | 구현됨 |
| 결제 버튼 연타·재시도 | 멱등키, 예매당 결제 1건 UNIQUE | 구현됨 |
| DB 커밋 후 Kafka 발행 실패 | Outbox | 구현됨 |
| 같은 메시지 두 번 도착 | Inbox(processed_events) | 구현됨 |
| 처리 불가 메시지 | 재시도 후 DLQ | 구현됨 |
| PG 장애 전파 | 서킷 브레이커 + Bulkhead | 구현됨 |
| Redis 장애 | 인증은 fail-closed(503) | 구현됨(인증만) |
| 결제 안 한 선점 | 선점 만료 스케줄러 | 구현됨 |
| 좌석·매수 수치 어긋남 | 좌석 원장 대사 | 구현됨(수동 호출) |
| 결제·예매 어긋남 | 결제 대사 RC-7·8 / RC-1~6 | 구현됨 / 꺼짐 |
| 과부하 뒤 회복 못 함 | 커넥션 대기 30초→1초 | 측정만 |

## 1. 결제↔예매 SAGA와 보상

> 비유: 여행사가 항공권과 호텔을 따로 예약한다. 호텔이 실패하면 항공권을 취소(보상)한다. 한 번에 묶는 대신 "실패하면 되돌리는 절차"를 미리 정해 둔다.

결제와 예매는 DB가 달라 한 트랜잭션으로 묶을 수 없다. 결제가 `payment.result`를 내면 예매가 받아 아래 규칙대로 처리한다(`PaymentResultService.java`, ADR 0027).

| 받은 결과 | 예매 상태 | 처리 |
|---|---|---|
| 승인 | HELD, 만료 전 | CONFIRMED, 좌석 SOLD, 티켓 발급 |
| 거절 | HELD | RELEASED, 좌석 반환 (보상) |
| 승인 | 선점 만료 뒤 | 환불 요청 `EXPIRED_AFTER_APPROVAL` |
| 승인 | 다른 결제가 이미 승인 | 환불 요청 `DUPLICATE_APPROVAL` |
| 승인 | 해제됨, 또는 금액·사용자 불일치 | 환불 요청 `NOT_CONFIRMABLE` + 이상 로그 |

만료 판단은 메시지를 받은 시각이 아니라 PG가 승인한 시각(`occurredAt`)으로 한다. Kafka가 밀려 늦게 도착해도 사용자 잘못이 아니면 확정해 준다.

AI 리뷰는 Reservation이 조정자가 되는 Orchestration을 권했지만 팀이 거부했다(SA MASTER AI 설계 검증 #3). 일반 예매 참여자가 둘뿐이라 결과 이벤트 구독으로 충분하다. 결제 요청이 예매보다 먼저 오는 경합은 선점 뒤에 발급한 서명 결제 토큰으로 막는다. 흩어진 흐름은 Zipkin 추적과 이벤트 흐름도로 보완한다.

## 2. PG 타임아웃: 실패가 아니라 "결과 모름"

> 비유: 택배를 보냈는데 배송 조회가 안 된다. 분실이라 단정하고 다시 보내면 두 개가 간다. 먼저 택배사에 전화해 확인한다.

PG 호출이 2초를 넘기면 PG가 실제로 승인했는지 알 수 없다. 이때 실패로 처리하면 "돈은 빠졌는데 표가 없는" 상태가 생긴다.

1. 결제를 `UNKNOWN`으로 두고 202를 준다. 좌석은 그대로 잡아 둔다.
2. 재확인 스케줄러(`PaymentRecheckService`)가 PG에 다시 묻는다. 간격은 10초×6 → 1분 → 5분 → 15분이고, 9회째에도 모르면 `NEEDS_MANUAL_CHECK`(약 22분 뒤)로 운영자에게 넘긴다.
3. PG에 주문이 없으면(`NotFound`) 그때 망취소(`preventLateApproval`)를 부른다. 늦게 도착한 승인을 막아 두고 `DECLINED(NOT_SENT)`로 닫는다.
4. 승인을 다시 보내지 않는다. 승인 호출은 결제당 1회다.

SA 검토에서 처음 제안한 "타임아웃 나면 바로 망취소"는 그대로 쓰지 않았다. 조회부터 하고 주문이 없을 때만 망취소한다(`docs/sa-review/02-pg-timeout-handling.md`). 알려진 한계로, TX1 커밋 응답이 유실되면 `DECLINED(NOT_SENT)`로 끝난다(ADR 0048).

## 3. 메시징 신뢰성: Outbox, Inbox, DLQ

### Outbox (보내는 쪽)

> 비유: 우체국에 직접 뛰어가지 않고 발송 대장에 먼저 적는다. 우편 담당자가 대장을 보고 부친다. 대장에 적혔으면 언젠가는 반드시 부쳐진다.

문제: DB 커밋은 됐는데 Kafka 발행 직전에 서버가 죽으면 이벤트가 사라진다.
해결: 결제 상태와 이벤트를 같은 트랜잭션으로 outbox 테이블에 쓴다. 릴레이가 500ms마다 `FOR UPDATE SKIP LOCKED`로 집어 `acks=all`로 보내고, 브로커 확인 뒤에만 `SENT`로 바꾼다(`common/.../messaging/outbox/`, ADR 0022).

- 일시 오류: 500ms~60초 지수 백오프(jitter)로 계속 재시도
- 영구 오류(직렬화·크기): `FAILED` + 알림, 운영자가 `outbox requeue`
- `SKIP LOCKED` 덕분에 인스턴스가 여러 대여도 같은 행을 두 번 보내지 않는다.

### Inbox (받는 쪽)

> 비유: 접수 대장에 우편 번호를 적고, 이미 적힌 번호면 다시 처리하지 않는다.

Kafka는 "최소 한 번" 전달이라 같은 메시지가 두 번 올 수 있다. `processed_events` 테이블에 eventId를 기록하고 상태 전이와 같은 트랜잭션에서 처리한다. 보관 기간(14일)이 지난 뒤 재전달될 때를 대비해 환불은 `refunds.payment_id` UNIQUE와 PG 멱등키로 한 번 더 막는다.

### 재시도와 DLQ

> 비유: 배달이 세 번 실패한 우편은 반송함에 모은다. 버리지 않으니 나중에 다시 처리할 수 있다.

1·2·4초로 3번 재시도한 뒤 `.dlq` 토픽으로 보낸다(`ConsumerErrorHandlers`). 다만 DB가 죽은 경우는 DLQ로 보내지 않고 무한 재시도한다. DB 장애는 기다리면 풀리는데, DLQ로 넘기면 "돈은 받았는데 티켓도 환불도 없는" 상태가 생기기 때문이다.

## 4. 서킷 브레이커, Bulkhead, 타임아웃

> 비유: 누전 차단기(서킷 브레이커)는 계속 합선이 나면 전기를 끊는다. 칸막이(Bulkhead)는 배에 물이 들어와도 한 칸만 잠기게 한다.

PG 호출은 Resilience4j 2.3.0으로 감쌌다(`Resilience4jPgApprovalGate.java`, ADR 0018).

| 그룹 | Bulkhead | 서킷 브레이커 |
|---|---|---|
| `pg-approve` (승인) | 동시 10, 대기 0 | 20회 중 50% 실패면 OPEN, 10초 뒤 HALF_OPEN에서 3회 시험 |
| `pg-recovery` (재확인·환불) | 동시 2 | 별도 상태 |

그룹을 나눠 승인 차단기가 열려도 재확인과 환불은 계속 돈다. 허가를 못 얻으면 PG를 부르지 않고 503 `PG_UNAVAILABLE`을 준다. `Unknown`은 실패로 세고, 정상 거절은 성공으로 센다. 카드 한도 초과는 PG 고장이 아니기 때문이다.

타임아웃 예산:

| 구간 | 값 |
|---|---|
| Gateway | 연결 1초, 응답 3초(상담만 10초) |
| PG | 연결 0.5초, 응답 2초 |
| 결제 DB | 커넥션 대기 1초, 쿼리 1.5초, 소켓 2초 |
| Redis 명령 | 100ms |

안쪽 시한이 바깥보다 짧아야 Gateway가 504로 끊기 전에 서비스가 의미 있는 오류를 낸다. 결제 DB가 멈췄을 때 504가 나던 문제를 이 예산으로 503으로 바꿨다(ISE-163, `Ft4DbFreezeScenarioTest`).

## 5. Redis 장애 정책과 대기열 보호

인증 블랙리스트는 fail-closed다(ADR 0005). Redis를 100ms 안에 못 읽거나 `auth-redis` 차단기가 열리면 503을 준다. 로그아웃한 토큰을 통과시키는 것보다 잠깐 막는 것이 안전하다는 판단이다.

> 비유: 출입 명단 시스템이 꺼지면 경비원은 아무나 들여보내지 않고 문을 잠근다.

| 기능 | Redis 장애 시 | 상태 |
|---|---|---|
| 로그아웃 블랙리스트 | 503 | 구현됨 |
| 입장 토큰 검증 | Redis 없이 서명만 검증 | 구현됨 |
| 대기열 진입·입장 | 503 | 차단기 미구현 |
| 사용자 요청 제한 | 로컬 카운터 | 미구현 |
| 공연·좌석 캐시 | DB로 대체 | 미구현 |

대기열 입장(`admit.lua`)은 세 가지를 막는다. 같은 초에 두 번 실행되지 않게 `lastTick`을 두어 인스턴스가 몇 대든 초당 50명을 넘지 않는다. `admitted`가 실제 대기 인원(seq)을 넘지 않게 해 오픈 순간 몰림 입장을 막는다. 발급 이력(`issued`)으로 토큰을 계속 재발급해 시간을 늘리는 것을 막는다.

## 6. 좌석 정합성: 만료와 대사

### 선점 만료

`SeatHoldExpiryScheduler`가 5초마다 기한 지난 HELD를 최대 100건 집어 건별 트랜잭션으로 푼다. 원장이 어긋나도 롤백하지 않고 경고만 남기고, 고치는 일은 대사가 맡는다. 추첨 예매는 1회 30분 연장한다.

### 좌석 원장 대사

> 비유: 가게 마감 정산. 영수증(예매 좌석)을 기준으로 진열대 표시(회차 좌석 상태)와 손님별 구매 수를 맞춘다.

`reservation_seats`를 정본으로 보고 `schedule_seats.status`와 `user_schedule_quotas.held_count`를 다시 계산해 다를 때만 고친다(`SeatLedgerReconciliationService`). 내부 API로 수동 호출하며, 주기 실행은 논의 중이다.

### 분산락을 쓰지 않은 이유

좌석 경합은 조건부 UPDATE + 부분 유니크 인덱스 + 좌석 ID 오름차순 잠금으로 막는다. Redis 분산락을 더하면 DB 제약과 이중이 되고, 락 만료나 Redis 지연이 선점 경로 전체를 막을 수 있다(SA MASTER 2202행).

동시성 결함 TS-1~4(데드락, 4매 한도 우회, 연타 PK 충돌 500, 만료 경합)는 실험 브랜치에서 수정안 S1~S4로 0건이 되는 것을 확인했다. 아직 main에는 반영되지 않았다(`docs/design/reservation/concurrency-results.md`).

## 7. 결제 대사와 운영 복구

`housekeeping` 실행기가 5분마다 결제 대사를 돌려 불일치를 `reconciliation_mismatches`에 기록하고 알린다. 데이터는 자동으로 고치지 않는다(ADR 0029). 지금은 결제 내부 규칙 RC-7(상태와 이력 불일치), RC-8(확정 이벤트 누락)만 돌고, 예매와 대조하는 RC-1~6은 설정으로 꺼져 있다.

고치는 일은 Ops 명령행이 한다. dry-run과 계획 해시, 4-eyes 확인, PG 근거로 목표 상태 결정, 감사 테이블 append-only 트리거를 둔다(ADR 0028).

## 8. 장애 주입과 시연

Mock PG는 `DELAY`, `DECLINE`, `DROP_RESPONSE`, `PROCESSING`, `SERVER_ERROR`, `REJECT`를 횟수·비율·주문 단위로 건다. `prod` 프로필과 장애 주입이 함께 켜지면 기동이 실패하게 막았다.

관리자 화면 `/admin/demo/faults`에서 고르는 시나리오(`docs/guides/demo-scenarios.md`):

1. 결제 거절 → 좌석 풀림
2. PG 지연 4초 → UNKNOWN → 재확인으로 확정
3. 응답 유실 → 원장 승인 1건만 증가(중복 결제 없음)
4. 서버 오류 지속 → 늦은 승인 방지 뒤 거절
5. 서킷 브레이커 OPEN → k6로 재현

2026-11-06 리허설: CB OPEN → 503 → 해제 11초 뒤 복구(F-09), 결제 서비스 재시작 13.9초 뒤 APPROVED(F-10) 통과. Redis 장애(E11)와 DLQ(E15)는 시연에서 뺐다.

알림은 Prometheus 규칙(`PaymentPgCircuitOpen`, `PaymentUnknownAging`, `PaymentOutboxFailed`, `PaymentDeadLetterOpen` 등)과 Loki 규칙에서 Alertmanager를 거쳐 슬랙으로 간다.

## 9. 트러블슈팅에서 배운 것

`docs/troubleshooting.md`에서 장애 대응과 이어지는 사례만 골랐다.

| 번호 | 증상 | 원인 | 배운 점 |
|---|---|---|---|
| TS-7 | Redis 타임아웃 뒤 복구 검증 실패 | 타임아웃을 "실행 안 됨"으로 가정 | 타임아웃은 결과 모름이다(PG와 같은 원리) |
| TS-17 | Gateway 기동 직후 첫 요청 503 | Redis 첫 연결이 100ms 초과 | PING 예열 뒤 준비 완료 |
| TS-34 | 배포 스모크 503 | Gateway의 Eureka 갱신에 약 35초 | 스모크를 120초까지 재확인 |
| TS-37 | 작업이 멈춰도 알림이 안 옴 | 지표 태그 `job`이 Prometheus 라벨과 충돌 | 알림은 실제 장애로 울려 봐야 한다 |
| TS-39 | 선점이 만료되지 않음 | 측정 때 만료를 꺼서 묵은 HELD 7,222건 적체 | 측정 전 초기 상태 확인 절차 |
| TS-40 | mock-pg 재기동 직후 503·504 | 연결 실패가 차단기 실패율로 셈 | 차단기 상태부터 본다 |
| TS-42 | 수 ms 만의 503을 Eureka 이탈로 오판 | 실제로는 `auth-redis` 차단기 OPEN | 503은 로그·지표로 출처부터 구분 |

## 면접 포인트

- "Outbox를 왜 썼나" → 결제 결과가 사라지면 돈과 예매가 어긋나 고객 피해가 가장 크다. DB 커밋과 발행을 같은 트랜잭션 기록으로 묶었다(SA MASTER AI 설계 검증 #6). 폴링 릴레이라 최대 500ms 발행 지연이 생긴다. CDC(Debezium) 방식과 비교한 기록은 저장소에 없으니, 면접에서 물으면 "추가 인프라 대 폴링 지연"으로 직접 비교해 말할 수 있게 준비한다.
- "Exactly-once를 어떻게 보장하나" → Kafka 전달은 최소 한 번으로 두고, 소비자가 processed_events와 DB UNIQUE로 멱등하게 처리해 결과적으로 한 번 반영되게 했다.
- "서킷 브레이커 fallback은 뭘 하나" → 실패를 확정하지 않는다. PG를 부르지 않았으니 결제 행도 만들지 않고 503만 준다.

## 참고

- `docs/design/payment/events-and-reliability.md`, `failure-scenarios.md`, `mock-pg.md`, `manual-recovery.md`
- `docs/design/reservation/hold-expiry.md`, `seat-ledger-reconciliation.md`
- ADR 0005, 0016~0020, 0022, 0023, 0026~0029, 0035, 0038, 0048
