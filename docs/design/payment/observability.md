# 결제 관측 지표와 알림

Micrometer로 지표를 내보내 Prometheus가 수집하고 Grafana 대시보드 1장과 알림 규칙으로 본다. 결정과 근거는 [0024](../../decisions/0024-payment-observability-metrics.md)에 있다. 결제 지표는 "돈이 확정되지 않은 채 남은 건과 그 나이"를 먼저 본다(INV-4·INV-6, [invariants.md](invariants.md)). 아래 이름은 ISE-156에서 구현하고 `PrometheusMeterRegistry.scrape()`로 확인한 실제 이름이다(`PrometheusNamesTest`, `DbAggregateGaugesTest`). 대사(ISE-43)와 Ops 명령(ISE-42) 지표는 아직 없다.

## 1. 지표

모든 지표에는 공통 설정이 `application` 태그(`management.metrics.tags.application`)를 붙인다. 서비스 구분은 그 태그로 한다. 이름 규칙: Micrometer 이름의 점은 밑줄이 되고, Counter는 `_total`, Timer는 `_seconds_count`·`_seconds_sum`(백분위 히스토그램이면 `_seconds_bucket`)이 붙는다.

| 영역 | Prometheus 이름 | 종류 | 라벨(낮은 카디널리티만) |
|---|---|---|---|
| 승인 API | `payment_approve_seconds_count`·`_sum`·`_bucket` | Timer(백분위 히스토그램) | `outcome`(approved·declined·unknown·linked·rejected·unavailable·error) |
| PG 호출 | `payment_pg_call_seconds_count`·`_sum` | Timer | `operation`(approve·find_payment·refund·find_refund·prevent_late_approval·approve_billing·issue_billing_key), `outcome`(approved·declined·refunded·refund_rejected·not_found·prevented·already_approved·billing_key_issued·billing_key_rejected·unknown·exception) |
| PG 호출 | `payment_pg_unknown_total` | Counter | `operation`, `cause`(timeout·connection_lost·pg_error·processing·client_error) |
| PG 호출 | `payment_pg_amount_mismatch_total` | Counter | `operation` |
| 보호 장치 | `resilience4j_circuitbreaker_state`·`_failure_rate`·`_not_permitted_calls_total`·`_calls_seconds_count` 등, `resilience4j_bulkhead_available_concurrent_calls`·`_max_allowed_concurrent_calls` | Resilience4j 제공 지표 | `name`(`pg-approve`, `pg-recovery`) |
| 결제 상태 | `payment_requested_oldest_seconds` | Gauge | — |
| 결제 상태 | `payment_unknown_count`, `payment_unknown_oldest_seconds` | Gauge | — |
| 결제 상태 | `payment_manual_check_count`, `payment_manual_check_oldest_seconds` | Gauge | — |
| 환불 | `refund_pending_count`, `refund_pending_oldest_seconds`(`REQUESTED`·`UNKNOWN`) | Gauge | — |
| 환불 | `refund_manual_check_count`, `refund_manual_check_oldest_seconds` | Gauge | — |
| 전이 | `payment_transition_conflict_total`(조건부 UPDATE 0행) | Counter | `operation`(tx2·recheck·refund_worker·consumer·dlq) |
| Outbox | `outbox_pending_oldest_seconds`, `outbox_failed_count`, `outbox_retrying_count` | Gauge | — |
| Outbox | `outbox_relay_last_success_timestamp`, `outbox_relay_failures_total`, `outbox_events_failed_total` | Gauge(epoch 초), Counter, Counter | — |
| DLQ | `payment_dlq_handler_failures_total` | Counter | `topic` |
| DLQ | `dead_letter_open_count`, `dead_letter_open_oldest_seconds` | Gauge | `topic`(원본 토픽) |
| DLQ | 컨슈머 랙 | Kafka 클라이언트 제공 지표 | 그룹 |
| 대사 | `reconciliation_mismatch_open` | Gauge | `rule`(RC-1~RC-8) — ISE-43 |
| 작업 | `payment_job_last_success_timestamp`, `payment_job_failures_total` | Gauge(epoch 초), Counter | `job`(recheck·refund-worker·outbox-cleanup·processed-events-cleanup·idempotency-cleanup·gauge-refresh) |
| DB 연결 | Hikari 대기 수, 연결 대기 시간 초과 | 제공 지표 사용 | 풀 이름 |
| Ops | `payment_ops_command_total` | Counter | `command`, `result`(applied·rejected) — ISE-42 |

규칙: `paymentId`·`userId`·`reservationId`·`traceId`·`eventId`·`orderId`·`refundId`는 라벨에 쓰지 않는다. `PaymentMetrics`의 메서드는 라벨 값을 enum으로만 받는다(예외: 등록된 고정 이름인 작업 이름과 리스너 상수인 토픽). `MetricLabelsTest`가 실제 컨텍스트의 결제·환불·outbox·dead_letter 지표 라벨을 훑는다. DB 집계 Gauge는 `housekeeping` 실행기의 `gauge-refresh` 작업이 15초 주기로 갱신하고 수집 때 DB를 조회하지 않는다.

초안과 달라진 점:
- 승인 결과별 건수는 별도 Counter(`payment_approve_total`)가 아니라 `payment_approve_seconds_count{outcome}`로 본다. `outcome`에 `unavailable`(승인 허가 거절 503)과 `error`(그 밖의 예외)를 더했다.
- 이름이 `payment_approve_duration`·`payment_pg_call_duration`에서 `..._seconds_*`로 바뀌었다(Micrometer Timer의 Prometheus 이름).
- 전이 충돌 이름이 `payment_transition_conflict_total`로 같고 `operation`의 `ops`는 Ops 명령 이슈(ISE-42) 때 더한다. 이전 초안의 `dlq_handler_failures_total`은 `payment_dlq_handler_failures_total`이다.
- `payment_job_*`의 `job` 값은 등록된 작업 이름이다(초안의 `outbox_relay`·`payment_recheck`·`refund_worker`·`cleanup`·`gauge_refresh`·`reconciliation`을 대체). Outbox 릴레이는 결제 공통 작업 실행기가 아니라 common의 전용 실행기라 `payment_job_*`가 아니라 `outbox_relay_*`로 따로 남긴다. `cleanup` 하나였던 값은 정리 작업 셋의 이름으로 나뉜다. `reconciliation`은 ISE-43에서 더한다.
- PG 호출의 `operation`에 `approve_billing`·`issue_billing_key`를 더했다. 자동결제(빌링키)도 같은 PG 클라이언트를 지나므로 지표 구멍이 되지 않게 했다.
- 건수 Gauge의 `_count` 접미사는 Prometheus 클라이언트가 거부하지 않아 목표 이름 그대로 쓴다(`payment_unknown_count` 등).
- `resilience4j_*`의 정확한 이름 목록은 Resilience4j 2.3.0 `Tagged*Metrics`가 정한다. 직접 만들지 않았다.

### 구현 메모 (ISE-156)

- **기록 위치**: 결제 지표는 `com.iseonjwa.payment.observability.PaymentMetrics` 한 클래스가 `MeterRegistry`를 감싸 이름·라벨을 한곳에 둔다. 값이 정해진 라벨 조합(승인 결과, PG 작업×Unknown 원인, 전이 경로)은 생성할 때 미리 등록해, 사건이 없어도 0인 시계열이 보인다(`increase()`와 알림이 "처음 발생"을 놓치지 않는다).
- **승인 결과**: `PaymentApprovalService.approve`의 `finally` 한 곳에서 호출마다 정확히 한 번 기록한다. 흐름의 각 갈래가 종류만 남기고(`Recording`), 예외로 끝나면 예외로 정한다. 같은 키의 재요청과 새 키 연결은 `linked`, 종료된 결제·다른 조건의 기존 결제를 거절하는 응답(409)과 `BusinessException`(4xx)은 `rejected`, 허가 거절 503은 `unavailable`, 그 밖의 5xx·예상 못 한 예외는 `error`다. 종류를 남기지 못한 정상 반환도 `error`로 세어 빠진 경로가 드러난다. PG 결과가 승인인데 금액이 달라 운영자 확인이 되는 요청은 `approved`로 세고, 금액 불일치는 `payment_pg_amount_mismatch_total`이 따로 센다. PG를 부르기 전에 토큰이 만료돼 거절로 닫은 요청은 `declined`다.
- **PG 호출**: `MeteredPgClient`가 실제 어댑터를 감싸 호출마다 시간과 결과 종류를 잰다. 서비스 코드는 PG 지표를 모른다. 결과가 `Unknown`이면 원인별 Counter도 올린다. 어댑터가 예외를 던지면 `outcome=exception`으로 기록하고 같은 예외를 다시 던진다(Unknown으로 바꾸는 것은 호출한 서비스의 몫이다).
- **금액 불일치**: 판단하는 모든 곳에서 올린다. 승인 TX2는 `approve`, 재확인은 `find_payment`와 `prevent_late_approval`, 환불 워커는 `refund`와 `find_refund`다.
- **전이 충돌**: 경로(`operation`)를 아는 호출한 유스케이스가 반환값 false(조건부 UPDATE 0행)를 받아 센다. `PaymentTransitionService`는 경로를 모르므로 세지 않는다. `tx2`는 승인 API와 자동결제의 TX2, `recheck`는 결제 재확인, `refund_worker`는 환불 워커(환불·결제 전이)다. `consumer`·`dlq`는 이름만 정했고 지금은 올리는 곳이 없다(소비자와 DLQ 처리기에는 조건부 UPDATE가 없다. 중복 판단은 UNIQUE와 `ON CONFLICT`가 한다).
- **Resilience4j**: CB와 Bulkhead를 `CircuitBreakerRegistry`·`BulkheadRegistry`에 두 그룹 이름으로 등록하고 `TaggedCircuitBreakerMetrics`·`TaggedBulkheadMetrics`(`MeterBinder` 빈)로 묶는다. 지표를 직접 만들지 않았다. `resilience4j-micrometer` 버전은 `resilience4j-bom`(spring-cloud-dependencies)이 정한다(2.3.0).
- **DB 집계 Gauge**: `DbAggregateGauges.refresh()`가 테이블마다 쿼리 하나(`FILTER`)로 읽어 메모리 스냅숏을 바꾸고 Gauge는 스냅숏을 읽는다. `housekeeping` 실행기의 `gauge-refresh` 작업이 기동 직후 한 번 바로 돌고(첫 지연 0) 이후 15초마다 돈다. 설정은 `payment.metrics.gauge-refresh.{enabled(true), interval(15s)}`이고 `payment.housekeeping.enabled`와 따로다(테스트 프로필은 끈다). 쿼리가 하나라도 실패하면 스냅숏을 바꾸지 않고 예외를 던져 이전 값이 남고 `payment_job_failures_total{job="gauge-refresh"}`가 오른다. 한 번 본 `dead_letters` 토픽은 해결된 뒤에도 0으로 남는다.
- **나이 기준 시각**: 나이는 갱신 시각의 시계에서 기준 시각을 뺀 초이고 행이 없으면 0이다. `REQUESTED`·`UNKNOWN` 결제, 환불 대기, Outbox `PENDING`, `dead_letters OPEN`은 `created_at`(돈이 확정되지 않은 채 남은 시간), `NEEDS_MANUAL_CHECK` 결제·환불은 `updated_at`(수동 확인으로 들어간 시각)이다. NMC 행을 제자리에서 고치는 코드는 없다(모든 UPDATE가 `status = from` 조건이고 NMC는 도착 상태일 뿐이다). Ops 명령(ISE-42)이 NMC 행을 제자리에서 고치게 되면 `updated_at`의 의미를 다시 본다. 재시도 중 Outbox 건수는 `PENDING`이면서 `attempts > 0`이다.
- **인덱스(EXPLAIN)**: 결제 `idx_payments_unsettled_created_at`, 환불 `idx_refunds_unsettled_created_at`, `dead_letters` `idx_dead_letters_open_created_at`을 탄다(결제 20만·Outbox SENT 30만 행 규모에서 Index Scan, 실행 0.02~0.04ms). Outbox는 조건을 `IN ('PENDING','FAILED')`로 쓰면 어느 부분 인덱스에도 맞지 않아 SENT가 쌓인 테이블 전체를 순차로 읽었다(12.7ms). `status = 'PENDING' OR status = 'FAILED'`로 쓰면 `idx_outbox_events_pending_id`와 `idx_outbox_events_failed_message_key`의 BitmapOr가 되어 두 상태의 행만 읽는다(0.06ms). 그래서 새 마이그레이션은 없다. `PENDING`이 크게 쌓이면(브로커 장애) 그만큼 행을 읽는다.
- **Outbox 릴레이 지표**: common의 `OutboxMetrics`가 `outbox_relay_last_success_timestamp`, `outbox_relay_failures_total`, `outbox_events_failed_total`을 남긴다. Micrometer는 common에서 `compileOnly`고 `MeterRegistry` 빈이 없으면 지표 없이 동작한다. "마지막 성공"은 라운드 반복이 예외 없이 끝난 시각이다. Kafka가 죽어 전송이 실패해도 라운드는 정상으로 끝나므로 이 값은 계속 갱신된다. 즉 릴레이 스레드가 살아서 돌고 있다는 신호일 뿐이고, 브로커 장애와 발행 지연은 `outbox_pending_oldest_seconds`가 잡는다. 두 작업 지표의 마지막 성공 시각은 기동 시각으로 시작한다(첫 주기 전에 정체 알림이 울리지 않게 하고, 한 번도 성공하지 못한 작업은 기준 시간이 지나면 정체로 보이게 한다).
- **미확인**: Grafana 패널과 알림 규칙(ISE-157), 실제 Prometheus 스크레이프 환경에서의 출력(테스트는 `scrape()` 문자열로 확인), 부하에서 `payment_approve_seconds_bucket`의 시계열 수와 메모리.

## 2. 알림 (초기값은 가정, 부하 테스트와 복구 정책에 맞춰 조정)

| 구분 | 조건 | 기준 | 복구 절차 |
|---|---|---|---|
| 즉시 확인 | `NEEDS_MANUAL_CHECK` 발생(결제·환불) | 건수 > 0 | [manual-recovery.md](manual-recovery.md) 2절 |
| 즉시 확인 | PG 승인·환불 금액 불일치 | `payment_pg_amount_mismatch_total` 증가 | 같은 문서 2절 |
| 즉시 확인 | `dead_letters` 미해결 | `OPEN` 건수 > 0 | 같은 문서 3절 |
| 즉시 확인 | DLQ 처리기 실패 | 실패 발생 또는 DLQ 그룹 랙 > 0이 5분 지속 | 같은 문서 3절 |
| 즉시 확인 | Outbox `FAILED` | 건수 > 0 | 같은 문서 4절 |
| 즉시 확인 | 결제·예매 대사 불일치 | `reconciliation_mismatch_open` > 0(규칙별) | 같은 문서 6절 |
| 경고 | `NEEDS_MANUAL_CHECK` 장기 미해결(결제·환불) | 최고령 > 30분(심각 24시간) | 같은 문서 2절 |
| 경고 | `REQUESTED` 체류(재확인이 집지 못함) | 최고령 > 60초 | 같은 문서 5절 |
| 경고 | `UNKNOWN` 장기 체류 | 최고령 > 5분 | 같은 문서 5절 |
| 경고 | 환불 대기 장기 체류 | 최고령 > 30분(심각 2시간) | 같은 문서 5절 |
| 경고 | PG Circuit Breaker OPEN | `pg-approve` 또는 `pg-recovery` OPEN | 같은 문서 5절 |
| 경고 | Outbox 발행 지연 | `PENDING` 최고령 > 5분(심각 30분) | 같은 문서 4절 |
| 경고 | 작업 정체 | 마지막 성공 이후 `outbox_relay_last_success_timestamp`·`job="gauge-refresh"` 1분, `job="recheck"`·`"refund-worker"` 2분, `reconciliation` 15분(ISE-43), 정리 작업 셋(`outbox-cleanup`·`processed-events-cleanup`·`idempotency-cleanup`) 1시간 초과 | 같은 문서 7절 |
| 경고 | DB 연결 대기 초과 | Hikari 연결 대기 시간 초과 증가 | 같은 문서 7절 |

- 즉시 확인은 돈이 확정되지 않았거나 추적이 끊길 수 있는 상태다. 경고는 자동 복구가 아직 진행 중이지만 늦어지는 상태다.
- `NEEDS_MANUAL_CHECK` 장기 미해결 기준(30분·24시간)은 [0020](../../decisions/0020-payment-refund-dlq-manual-check.md)의 값이고, 이전 초안의 표에서 빠져 있던 것을 넣었다.
- `REQUESTED` 60초 기준은 TX1이 `next_check_at`을 10초 뒤로 채우고 재확인이 그 시각에 집는다는 전제다. 넘으면 스케줄러 정지나 `next_check_at` 누락을 의심한다(CHECK 제약이 누락을 막는다).

## 3. 대시보드 구성 (1장)

| 줄 | 패널 |
|---|---|
| 1. 돈이 걸린 미해결 | 결제·환불 NMC 건수와 최고령, 환불 대기 최고령, `dead_letters` OPEN, 대사 불일치(규칙별) |
| 2. 승인 흐름 | 승인 결과별 건수, 승인 지연 p95·p99, PG 호출 지연, `Unknown` 원인 |
| 3. 보호 장치 | CB 상태·Bulkhead 점유(그룹별), Hikari 대기 |
| 4. 이벤트 | Outbox `PENDING` 최고령·`FAILED`, DLQ 랙, 전이 충돌 |
| 5. 작업 | 작업별 마지막 성공 시각, Ops 명령 실행 수 |

## 4. 산출물 위치
대시보드는 `infra/grafana/dashboards/`에 1장, 알림 규칙은 Grafana 또는 Prometheus 규칙 파일(`infra/`)에 둔다. 알림 설명에는 위 복구 절차 절을 링크한다. 분산 추적 보강과 추가 대시보드는 후속 개선이다.
