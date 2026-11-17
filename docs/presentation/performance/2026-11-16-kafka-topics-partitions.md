# Kafka 토픽별 파티션 수·키와 그 근거 (ISE-265)

발제 필수 "대용량 트래픽"의 산출물 중 "파티션 수·키를 고른 이유"다. 형식은 [`evidence-formats.md`](../evidence-formats.md) T7을 따른다. Kafka-UI 캡처(토픽 목록, 컨슈머 그룹 랙)는 배포 단계 측정(ISE-296)의 접수형 Outbox 단계(O2)에서 찍어 4절에 더했다.

- 기준: dev `54f18b63`(2026-11-16)의 코드·설정·문서. 근거가 문서에 없으면 "근거 없음"으로 적었다(지어내지 않는다).
- 배포 서버의 실제 파티션 수는 2026-11-17 Kafka-UI 토픽 목록으로 확인했다. 1절 표와 같다(5절).

## 1. 한눈에

배포에서 쓰는 토픽은 6개다(업무 3, DLQ 3). Kafka를 쓰는 서비스는 reservation-service와 payment-service뿐이다.

| 토픽 | 보내는 곳 | 키 | 파티션 | 받는 곳(컨슈머 그룹, 동시성) | 근거 |
|---|---|---|---|---|---|
| `seat-hold.request` | reservation 접수형 선점(Outbox) | `{scheduleId}:{section}` | 6(코드에서 선언) | reservation `reservation.seat-hold`, 2 | 키: ADR 0056, SA 14장 #5. 6이라는 수: "동시성보다 넉넉히"만 있음 |
| `payment.command` | reservation 환불·자동결제(Outbox) | `reservationId` | 3(코드에서 선언) | payment `payment.command`, 3 | ADR 0051 |
| `payment.result` | payment 결제 결과(Outbox) | `reservationId` | 1(자동 생성) | reservation `reservation.payment-result`, 1 | 키: SA 10-1. 파티션 수: 근거 없음 |
| `seat-hold.request.dlq` | 공통 오류 처리기 | 원래 키 | 1(자동 생성) | reservation `reservation.seat-hold-dlq`, 1 | 선언하지 않는 이유만 있음 |
| `payment.command.dlq` | 공통 오류 처리기 | 원래 키 | 1(자동 생성) | payment `payment.command-dlq`, 1 | 선언하지 않는 이유만 있음 |
| `payment.result.dlq` | 공통 오류 처리기 | 원래 키 | 1(자동 생성) | reservation `reservation.payment-result-dlq`, 1 | 근거 없음 |

- Outbox 행의 `message_key`가 그대로 Kafka 키가 된다(`OutboxWriter.write` → `OutboxRelay` → `KafkaOutboxEventSender.send(topic, key, payload)`). 생산자는 `acks=all`, 멱등 전송이다(`config-repo/application.yml`).
- 자동 생성 토픽: compose에 `KAFKA_NUM_PARTITIONS`가 없어 브로커 기본값(파티션 1, 복제 1)이다. 로컬에서 확인한 기록은 [`2026-11-04-kafka-basics.md`](../../learning/junhyun/qna/2026-11-04-kafka-basics.md)에 있다.
- 로컬 전용 `bootstrap.integration`(`@Profile("local")` 연결 확인)은 배포에 없어 뺐다.

## 2. 토픽별 근거 문단

**`seat-hold.request`**: 키는 `{scheduleId}:{section}`이라 같은 회차·구역의 선점 요청은 한 파티션에서 차례로 처리된다. 같은 구역 좌석을 두고 다투는 요청을 한 줄로 세워 행 잠금 경합을 줄이는 것이 목적이다. 회차만 키로 쓰면 인기 회차 하나가 파티션 하나·소비자 하나에 묶여 구역까지 넣었다. 요청 좌석이 여러 구역에 걸치면 사전순으로 가장 작은 구역을 키로 쓴다. 파티션 6개, 소비자 동시성 2다. 소비자 스레드 하나는 처리하는 동안 DB 커넥션 하나를 쥐고, 풀 5개를 접수 POST·결과 폴링·좌석 조회와 나눠 쓰므로 동시성을 2로 둔다. 파티션은 나중에 늘리면 같은 키가 다른 파티션으로 옮겨 키별 순서가 잠시 흔들려서, 바로 바꿀 수 있는 동시성보다 넉넉히 둔다. 6이라는 숫자 자체를 고른 기록은 없다.
근거: [ADR 0056](../../decisions/0056-async-seat-hold-key-for-mixed-sections.md) 12·22·34줄, SA MASTER 14장 #5, `SeatHoldKafkaConfiguration`(파티션·동시성 주석), `config-repo/reservation-service.yml` `seat-hold.consumer`
- 배포 기본은 동기형(`seat-hold.mode: sync`)이다. 리스너와 토픽은 늘 켜지지만 메시지는 접수형으로 띄울 때만 흐른다.

**`payment.command`**: 키는 `reservationId`라 같은 예매의 자동결제 명령과 환불 명령은 한 파티션에서 순서대로 처리된다. 이 키는 결제 서비스의 멱등 키이기도 해서 릴레이가 같은 명령을 다시 보내도 결제는 한 건이다. 파티션 3개, 소비자 동시성 3이다. 소비자는 리스너 스레드에서 PG 응답을 기다리므로 동시 처리 수가 파티션 수에 묶인다. 11/10 측정에서 소비자 1개가 PG 응답을 직렬로 기다려 자동결제가 초당 약 3.2건에 그쳤고, 그래서 파티션과 동시성을 3으로 올렸다. 동시성은 PG 자동결제 동시 허가(3)와 같고, 파티션 수보다 크면 기동이 실패하게 막았다.
근거: [ADR 0051](../../decisions/0051-lottery-billing-consumer-concurrency-and-billing-bulkhead.md) 16·24·83줄, [`events-and-reliability.md`](../../design/payment/events-and-reliability.md) 65·66줄, `config-repo/payment-service.yml` `payment.command`

**`payment.result`**: 키는 `reservationId`라 같은 예매의 결제 결과는 순서대로 처리된다(SA 10-1 토픽 표, 10-4 "순서는 같은 키 안에서만 보장된다"). 파티션은 자동 생성된 1개, 소비자 동시성 1이다. 파티션 수를 정한 기록은 없다(근거 없음). 오픈 피크 계획에 파티션·소비자 3개로 늘리는 안(R-2)이 있지만 반영되지 않았다.
근거: `PaymentResultPublisher`(키 주석), SA MASTER 10-1·10-4, [`open-peak-load-plan.md`](../../design/infra/open-peak-load-plan.md) 270·288줄

**DLQ 세 개(`<토픽>.dlq`)**: 처리 실패는 1·2·4초 간격으로 3번 다시 시도한 뒤 원래 키 그대로 `<토픽>.dlq`로 보낸다. 다시 시도할 수 없는 오류는 바로 보낸다. DLQ는 코드에서 선언하지 않아 파티션 1개로 자동 생성된다. 선언하지 않는 이유는 기록돼 있다: DLQ 전송은 파티션을 지정하지 않으므로 원래 토픽과 파티션 수가 같을 필요가 없다. 파티션을 1개로 둔 이유는 따로 없다(근거 없음). DLQ 소비자는 1초에서 60초까지 늘려 가며 끝없이 다시 시도한다.
근거: `ConsumerErrorHandlers`(공통), `SeatHoldKafkaConfiguration` 24줄, `PaymentCommandKafkaConfiguration` 36·37줄, [`events-and-reliability.md`](../../design/payment/events-and-reliability.md) 68·71줄

## 3. 컨슈머 그룹을 나눈 방식

- 토픽마다 그룹을 하나씩 둔다(`reservation.seat-hold`, `payment.command`, `reservation.payment-result`). DLQ는 원래 그룹 이름에 `-dlq`를 붙인 별도 그룹이다. 원래 토픽의 랙과 DLQ 처리를 따로 보려는 구성으로 보이지만, 나눈 이유를 적은 기록은 찾지 못했다(근거 없음).

## 4. 캡처 (2026-11-17, 배포 서버)

배포 단계 측정 O2(풀 5, 동시 처리 상한 4, 접수형 Outbox)의 K1-H 램프가 320/s 구간에 들어선 때(시작 뒤 285초부터, 08:58:57~08:59:41 KST) 찍었다. 측정자 PC에서 터널(`ssh -N -L 8085:127.0.0.1:8085 iseonjwa`)로 Kafka-UI를 열고 헤드리스 Chrome(Playwright)으로 저장했다. 측정 결과와 해석: [`deploy-seathold-steps-results.md`](../../design/reservation/deploy-seathold-steps-results.md) 9절.

| 화면 | 파일 | 보이는 것 |
|---|---|---|
| 토픽 목록 | [`2026-11-17-kafka-ui-topics.png`](2026-11-17-kafka-ui-topics.png) | `seat-hold.request` 6, `payment.command` 3, `payment.result`·DLQ 세 개 1 |
| `seat-hold.request` 개요 | [`2026-11-17-kafka-ui-seat-hold-topic.png`](2026-11-17-kafka-ui-seat-hold-topic.png) | 파티션 6개에 메시지가 5,778~9,874건씩 나뉘어 있다 |
| `seat-hold.request` 메시지 | [`2026-11-17-kafka-ui-seat-hold-messages.png`](2026-11-17-kafka-ui-seat-hold-messages.png) | 키가 `{scheduleId}:{section}`(`299:B`, `299:W`)이고, 같은 키는 같은 파티션(`299:B` → 3)으로 간다 |
| `reservation.seat-hold` 그룹 | [`2026-11-17-kafka-ui-seat-hold-consumer.png`](2026-11-17-kafka-ui-seat-hold-consumer.png) | 멤버 2(동시성 2)가 파티션을 3개씩 나눠 맡는다. 이 순간 lag 32 |
| 컨슈머 그룹 목록 | [`2026-11-17-kafka-ui-consumer-groups.png`](2026-11-17-kafka-ui-consumer-groups.png) | 그룹 6개(업무 3, `-dlq` 3)가 모두 STABLE. `payment.command` 멤버 3 |

- 이 측정은 좌석 선점만 해서 결제 토픽에는 새 메시지가 흐르지 않았다. 결제 그룹은 멤버 수와 상태만 보인다.
- 메시지 화면에서 `299:B`가 약 0.55초 간격(초당 약 2건)으로 들어온다. 구역 키 하나에 접수가 몰리면 Outbox 릴레이가 그 키를 한 번에 1건씩만 보내서다(같은 키의 앞선 PENDING 행이 있으면 다음 행을 집지 않는다). 그래서 Kafka lag는 0~32로 작고, 대기는 Kafka 앞 Outbox 테이블에 쌓였다(PENDING 최대 1,941건). 키를 구역 단위로 둔 선택(2절)의 대가로, 결과 문서 9-3절에 정리했다.

## 5. 배포 서버에서 확인한 것

- 2026-11-17 Kafka-UI 토픽 목록(4절 첫 화면)에서 파티션 수가 1절 표와 같음을 확인했다. 자동 생성 토픽(`payment.result`, DLQ 세 개)은 1개, `seat-hold.request` 6개, `payment.command` 3개다. 복제 수는 모두 1이다.
- [ADR 0051](../../decisions/0051-lottery-billing-consumer-concurrency-and-billing-bulkhead.md) 20줄의 "배포 서버 값은 확인 못 함"은 이 확인으로 채워진다. ADR 본문은 고치지 않았다.
