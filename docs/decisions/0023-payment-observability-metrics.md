# 0023. 결제는 핵심 지표와 대시보드 1장, 금전 정합성 알림을 MVP에 둔다

- 상태: 제안 (SA 변경 요청 필요. 2026-10-28 개정)
- 날짜: 2026-10-27
- 결정자: 안준현
- 관련 AI 판단: [결제 상세 설계 빈틈 결정](../ai-judgments/junhyun/2026-10-27-payment-design-gaps.md)
- 관련 결정: [0017](0017-payment-pg-permit-before-tx1.md), [0019](0019-payment-refund-dlq-manual-check.md), [0021](0021-outbox-retry-and-retention.md), [0022](0022-payment-pg-client-port.md), [0027](0027-payment-ops-commands.md), [0028](0028-payment-reservation-reconciliation.md)
- 불변 조건: INV-4, INV-6의 탐지 수단([invariants.md](../design/payment/invariants.md))

## 맥락

SA H10은 Prometheus·Grafana·Zipkin을 Must로 두고 10-5는 `NEEDS_MANUAL_CHECK`의 Grafana 건수 지표와 알림 기준을 말한다. 그러나 결제 서비스가 무엇을 내보낼지, 어떤 상태에 알림을 걸지는 정해져 있지 않다. 앞선 결정(0017~0022)이 새 지표 요구를 여럿 만들었다. 결제는 지표 없이 장애를 알아채기 어렵고, 돈이 걸린 상태(미확정·수동 확인)는 오래 두면 사고가 된다. 지표는 발표와 부하 측정의 근거이기도 하다. 저장소에는 이미 `infra/prometheus.yml`, `infra/grafana/`가 있다.

2026-10-28 검토에서 `REQUESTED` 체류를 보는 지표가 없어 재확인 시각이 빠진 결제를 놓칠 수 있고(H4), 0019의 `NEEDS_MANUAL_CHECK` 장기 미해결 알림이 관측 문서에서 빠졌다는 점(L6)이 나왔다. 이후 결정(0019 개정, 0027, 0028)도 환불 대기·대사·Ops 지표를 요구한다.

## 결정

Micrometer + Prometheus + Grafana로 핵심 결제 지표를 수집하고, Grafana 대시보드 1장과 필수 알림 규칙을 MVP에 구성한다. 고급 분산 추적과 추가 대시보드는 후속 개선으로 둔다. 지표 이름·라벨·알림 기준의 기준 위치는 [observability.md](../design/payment/observability.md)다.

### 1. 지표

| 영역 | 지표 | 종류 |
|---|---|---|
| 승인 API | 지연 p50·p95·p99 | Timer(백분위 히스토그램) |
| 승인 API | 결과별 건수(`APPROVED`·`DECLINED`·`UNKNOWN`·`REJECTED`(요청 거절)·`LINKED`(기존 결제 연결)) | Counter |
| PG 호출 | 호출 지연(승인·조회·환불별), `Unknown` 원인(0022), 금액·통화 불일치 | Timer, Counter |
| 보호 장치 | Circuit Breaker 상태, Bulkhead 점유율(`pg-approve`, `pg-recovery`) | Resilience4j 제공 Micrometer 지표를 그대로 사용 |
| 결제 상태 | `REQUESTED` 최고령(초), `UNKNOWN`·`NEEDS_MANUAL_CHECK` 현재 건수와 최고령(초) | Gauge |
| 환불 | 대기(`REQUESTED`·`UNKNOWN`)와 `NEEDS_MANUAL_CHECK`의 건수와 최고령 | Gauge |
| 전이 | 조건부 UPDATE 0행 수(경로별) | Counter |
| Outbox | `PENDING` 최고령(초), `FAILED` 건수, 재시도 중 건수 | Gauge |
| DLQ | DLQ 처리기 실패, `dead_letter` OPEN 건수와 최고령, 컨슈머 랙 | Counter, Gauge |
| 대사 | 규칙별 미해결 불일치 수 | Gauge |
| 작업 | 작업별 마지막 성공 시각, 실패 수 | Gauge, Counter |
| DB 연결 | Hikari 대기, 연결 대기 시간 초과 | 제공 지표 사용 |
| Ops | 명령별 실행 수(적용·거부) | Counter |

### 2. 알림 규칙

| 구분 | 조건 | 초기 기준`[가정]` |
|---|---|---|
| 즉시 확인 | `NEEDS_MANUAL_CHECK` 발생(결제·환불) | 건수 > 0 |
| 즉시 확인 | PG 승인·환불 금액 불일치 | 발생 |
| 즉시 확인 | `dead_letter` 미해결 | `OPEN` > 0 |
| 즉시 확인 | DLQ 처리기 실패 | 실패 건 발생 또는 재시도 중 건 > 0이 5분 지속 |
| 즉시 확인 | Outbox `FAILED` | 건수 > 0 |
| 즉시 확인 | 결제·예매 대사 불일치 | 규칙별 > 0 |
| 임계값 경고 | `NEEDS_MANUAL_CHECK` 장기 미해결 | 최고령 > 30분(심각 24시간, 0019) |
| 임계값 경고 | `REQUESTED` 체류 | 최고령 > 60초 |
| 임계값 경고 | `UNKNOWN` 장기 체류 | 최고령 > 5분 |
| 임계값 경고 | 환불 대기 장기 체류 | 최고령 > 30분(심각 2시간) |
| 임계값 경고 | PG Circuit Breaker OPEN | 그룹별 |
| 임계값 경고 | Outbox 발행 지연 | `PENDING` 최고령 > 5분(심각 30분, 0021) |
| 임계값 경고 | 작업 정체 | 작업별 마지막 성공 이후 기준 초과(observability.md) |
| 임계값 경고 | DB 연결 대기 초과 | 증가 |

임계값은 초기값이다. 부하 테스트 결과와 실제 복구 정책(재조회 간격 최대 약 21분, SA 9-4)에 맞춰 조정하고, 바꿀 때 이 ADR을 갱신한다. `UNKNOWN` 경고 5분은 재조회 간격(10초×6회 → 1분 …)과 비교해 오탐이 많으면 늘린다.

### 3. 구현 규칙
- **낮은 카디널리티**: 지표 라벨에는 `paymentId`, `userId`, `reservationId`, `traceId` 같은 고유 식별자를 쓰지 않는다. 결과 상태, PG 작업 종류, 제한된 오류 분류, 대사 규칙 번호, 작업 이름 같은 값만 쓴다. 건별 추적은 로그와 Zipkin `traceId`로 한다.
- **DB 집계 Gauge는 주기적으로 갱신**한다. 수집(스크레이프)마다 DB를 조회하지 않고, 정리 실행기가 일정 주기(예: 15초`[가정]`)로 집계해 메모리 값을 갱신하며 Gauge는 그 값을 읽는다. 집계 쿼리는 부분 인덱스(`INDEX(status, created_at) WHERE ...` 등)를 쓴다.
- **Resilience4j**의 CB·Bulkhead 지표는 Micrometer 연동을 켜서 그대로 쓰고 따로 만들지 않는다.
- **Runbook**: 모든 필수 알림에 원인 확인과 복구 절차를 간단히 문서화한다(`docs/design/payment/manual-recovery.md`에 알림별 절을 둔다). 알림 규칙의 설명에 그 절을 링크한다.

### 4. 산출물
- 지표 이름·라벨 정의(`docs/design/payment/observability.md`)
- Grafana 대시보드 1장(`infra/grafana/dashboards/`), 알림 규칙(Grafana 또는 Prometheus 규칙 파일), Runbook

## 검토한 대안

- **지표만 내보내고 대시보드는 나중에**: 작업이 가장 적다. 알림이 없는 동안 돈과 관련된 미확정 상태를 놓칠 수 있어 채택하지 않았다.
- **Gauge를 수집 시점에 DB에서 바로 조회**: 값이 항상 최신이다. 수집 주기마다 쿼리가 돌아 DB 부하가 늘고 수집이 DB 지연에 묶인다.

## 트레이드오프

| | 얻는 것 | 감수하는 것 |
|---|---|---|
| 핵심 지표 + 대시보드 + 알림 | 금전 정합성 문제를 바로 알고 발표·부하 측정 근거가 생긴다 | M2~M3 작업량이 늘고 알림 임계값을 조정해야 한다 |
| 주기 갱신 Gauge | DB 부하가 작고 수집이 빠르다 | 값이 최대 갱신 주기만큼 늦다 |
| 낮은 카디널리티 | Prometheus 메모리·성능이 안정적이다 | 건별 원인은 지표가 아니라 로그·트레이스로 찾아야 한다 |

## 결과

- SA 변경 요청: S-12(9장 또는 12장에 결제 지표·알림 목록, 10-5의 "Grafana 건수 지표와 알림 기준"을 이 표로 구체화).
- 이슈 반영: 지표·대시보드·알림·Runbook을 M2(승인 지표)와 M3(복구·대사 관련 지표·알림)로 나누어 완료 기준에 넣는다.
- 테스트: 지표 등록·이름, 라벨에 고유 식별자가 없음, Gauge 갱신 주기, 알림 규칙 문법 검증, 시나리오 주입(PG 지연, DLQ 적재, `NEEDS_MANUAL_CHECK` 생성, 대사 불일치) 시 지표 변화.

## 개정 이력

- 2026-10-28: `REQUESTED` 최고령(H4), 환불 대기, 금액 불일치, 전이 0행, `dead_letter` OPEN, 대사, 작업별 마지막 성공, DB 연결, Ops 명령 지표를 더했다. 0019의 `NEEDS_MANUAL_CHECK` 장기 미해결 알림을 알림 표에 넣었다(L6). 이전 초안의 `dlq_pending`과 정리 작업 전용 지표는 `observability.md`에서 이름을 바꿨다.

## 면접에서 설명할 한 줄

결제에서는 "에러율"보다 "돈이 확정되지 않은 채 남은 건과 그 나이"가 중요한 지표라고 보고, 그것을 최고령 Gauge와 즉시·임계값 알림으로 관측하게 하되 지표 라벨에는 고유 식별자를 넣지 않았다.
