# 결제 관측 지표와 알림

Micrometer로 지표를 내보내 Prometheus가 수집하고 Grafana 대시보드 1장과 알림 규칙으로 본다. 결정과 근거는 [0024](../../decisions/0024-payment-observability-metrics.md)에 있다. 지표 이름은 구현 때 확정하며 아래는 초안이다. 결제 지표는 "돈이 확정되지 않은 채 남은 건과 그 나이"를 먼저 본다(INV-4·INV-6, [invariants.md](invariants.md)).

## 1. 지표

| 영역 | 지표 | 종류 | 라벨(낮은 카디널리티만) |
|---|---|---|---|
| 승인 API | `payment_approve_duration` p50·p95·p99 | Timer | `outcome` |
| 승인 API | `payment_approve_total` | Counter | `outcome`(approved·declined·unknown·rejected·linked) |
| PG 호출 | `payment_pg_call_duration` | Timer | `operation`(approve·find_payment·refund·find_refund·prevent_late_approval), `outcome` |
| PG 호출 | `payment_pg_unknown_total` | Counter | `operation`, `cause` |
| PG 호출 | `payment_pg_amount_mismatch_total` | Counter | `operation` |
| 보호 장치 | Resilience4j CB 상태, Bulkhead 점유 | 제공 지표 사용 | 인스턴스(`pg-approve`, `pg-recovery`) |
| 결제 상태 | `payment_requested_oldest_seconds` | Gauge | — |
| 결제 상태 | `payment_unknown_count`, `payment_unknown_oldest_seconds` | Gauge | — |
| 결제 상태 | `payment_manual_check_count`, `payment_manual_check_oldest_seconds` | Gauge | — |
| 환불 | `refund_pending_count`, `refund_pending_oldest_seconds`(`REQUESTED`·`UNKNOWN`) | Gauge | — |
| 환불 | `refund_manual_check_count`, `refund_manual_check_oldest_seconds` | Gauge | — |
| 전이 | `payment_transition_conflict_total`(조건부 UPDATE 0행) | Counter | `operation`(tx2·recheck·refund_worker·consumer·dlq·ops) |
| Outbox | `outbox_pending_oldest_seconds`, `outbox_failed_count`, `outbox_retrying_count` | Gauge | — |
| DLQ | `dlq_handler_failures_total` | Counter | `topic` |
| DLQ | `dead_letter_open_count`, `dead_letter_open_oldest_seconds` | Gauge | `topic` |
| DLQ | 컨슈머 랙 | Kafka 클라이언트 제공 지표 | 그룹 |
| 대사 | `reconciliation_mismatch_open` | Gauge | `rule`(RC-1~RC-8) |
| 작업 | `payment_job_last_success_timestamp`, `payment_job_failures_total` | Gauge, Counter | `job`(outbox_relay·payment_recheck·refund_worker·cleanup·gauge_refresh·reconciliation) |
| DB 연결 | Hikari 대기 수, 연결 대기 시간 초과 | 제공 지표 사용 | 풀 이름 |
| Ops | `payment_ops_command_total` | Counter | `command`, `result`(applied·rejected) |

규칙: `paymentId`·`userId`·`reservationId`·`traceId`는 라벨에 쓰지 않는다. DB 집계 Gauge는 `housekeeping` 실행기가 15초`[가정]` 주기로 갱신하고 수집 때 DB를 조회하지 않는다. 이전 초안의 `dlq_pending`은 `dead_letter_open_count`로, `cleanup_last_success_timestamp`·`cleanup_failures_total`은 `payment_job_*{job="cleanup"}`으로 바꿨다.

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
| 경고 | 작업 정체 | 마지막 성공 이후 `outbox_relay`·`gauge_refresh` 1분, `payment_recheck`·`refund_worker` 2분, `reconciliation` 15분, `cleanup` 1시간 초과 | 같은 문서 7절 |
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
