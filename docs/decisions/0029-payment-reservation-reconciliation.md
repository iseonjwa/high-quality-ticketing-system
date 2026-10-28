# 0029. 결제·예매 대사는 결제 서비스가 예매 서비스의 내부 조회 API로 하고, MVP는 기록과 알림만 한다

- 상태: 제안 (반효승 협의 필요, SA 변경 요청 필요. 서비스 경계 해법은 AI 제안이라 사용자 확인 필요)
- 날짜: 2026-10-28
- 결정자: 안준현(M3에 최소 대사, 상태·금액·환불 여부 대조, 허용 지연 초과 탐지, MVP는 자동 수정 없이 기록과 Grafana 알림은 사용자 결정)
- 관련 AI 판단: [결제 상세 설계 빈틈 결정](../ai-judgments/junhyun/2026-10-27-payment-design-gaps.md)
- 관련 결정: [0020](0020-payment-refund-dlq-manual-check.md), [0022](0022-outbox-retry-and-retention.md), [0027](0027-reservation-payment-event-contract.md), [0028](0028-payment-ops-commands.md), [0008](0008-internal-trust-and-auth-design.md)
- 불변 조건: INV-6, 그리고 INV-3·INV-4·INV-5·INV-7의 탐지 수단([invariants.md](../design/payment/invariants.md))

## 맥락

SA 3장은 메시지 신뢰성을 "outbox·처리 이력·예매 상태를 대사 쿼리로 맞춰 유실 0건·중복 처리 0건 확인"으로 검증한다고 적지만 결제와 예매 사이 대사는 설계되지 않았다. 0020는 "Reservation의 EXPIRED·CANCELED 예매와 Payment의 환불 상태를 맞춰 보는 대사"를 후속 과제로 남겼다(Opus 검토 H8).

각 장치는 자기 쪽 실패만 본다. Outbox 지표는 발행 정체를, DLQ 지표는 소비 실패를 본다. Reservation이 `RefundRequested`를 아예 내지 못한 경우나 예매가 결제 없이 확정된 경우처럼 두 서비스 사이에서 생긴 어긋남은 어느 쪽 지표에도 나타나지 않는다.

서비스마다 DB가 따로 있고(SA 7-1, 12-1) 결제 서비스는 예매 DB를 읽지 못한다. 대사를 어디서 어떻게 할지가 이 결정의 핵심이다.

## 결정

1. **주체는 Payment**: 돈의 원장이 Payment라 가장 심각한 불일치(돈은 받았는데 티켓도 환불 의무도 없음)를 Payment가 책임진다. 대사 작업은 `payment-service`의 `housekeeping` 실행기에서 5분`[가정]`마다 돈다.
2. **경계 해법은 예매 서비스의 읽기 전용 내부 조회 API**: Reservation이 대사용 조회 API를 제공하고 Payment가 호출한다.
   - `GET /internal/reconciliation/reservations?ids=…`(최대 100건): 예매별 `reservationId, userId, status, totalAmount, paymentId, holdExpiresAt, updatedAt`
   - `GET /internal/reconciliation/reservations/changes?from=…&to=…&cursor=…`: 기간 안에 `CONFIRMED`·`CANCELED`가 된 예매(결제 쪽에서 출발하면 찾지 못하는 RC-4용)
   - Gateway가 `/internal/**`를 외부에서 막는다(0008 A1). 서비스 간 신뢰는 SA 7-2·0008의 내부망 모델을 따른다. 응답에는 ID·상태·금액·시각만 넣고 이메일 같은 개인정보는 넣지 않는다.
   - Payment는 Eureka로 찾아 호출하고 타임아웃 2초`[가정]`, 실패하면 이번 대사를 실패로 기록한다(재시도는 다음 주기).
3. **대상 범위**: 최근 7일`[가정]` 안에 갱신된 결제와, 같은 기간의 예매 변경 목록이다. `REQUESTED`·`UNKNOWN`·`NEEDS_MANUAL_CHECK` 결제는 결제 쪽 지표가 맡으므로 대사에서 뺀다.
4. **규칙**: 허용 지연 L = 10분`[가정]`. 결제·예매 중 늦게 갱신된 쪽 기준으로 L이 지나도 맞지 않으면 불일치다.

   | 규칙 | 조건 | 뜻 |
   |---|---|---|
   | RC-1 | 결제 `APPROVED`, 환불 행 없음, 예매 `HELD` | 승인됐는데 확정되지 않음 |
   | RC-2 | 결제 `APPROVED`, 환불 행 없음, 예매 `EXPIRED`·`RELEASED`·`CANCELED` 또는 예매 없음 | 환불 의무가 등록되지 않음(가장 심각) |
   | RC-3 | 결제 `REFUNDED`, 예매 `CONFIRMED`이고 `payment_id`가 이 결제 | 환불됐는데 확정 유지 |
   | RC-4 | 예매 `CONFIRMED`인데 `payment_id` 결제가 없거나, `APPROVED`가 아니거나(`REFUNDED`는 RC-3), 그 결제의 `reservation_id`가 다름 | 승인 없는 확정 |
   | RC-5 | 결제 `APPROVED`·`REFUNDED`에서 `amount` ≠ 예매 `total_amount`, 또는 `REFUNDED` 환불 금액 ≠ `pg_approved_amount` | 금액 불일치 |
   | RC-6 | 결제 `user_id` ≠ 예매 `user_id` | 사용자 불일치 |
   | RC-7 | 결제·환불의 현재 상태 ≠ 이력(`target_type`별)의 마지막 `to_status` | 이력 없는 상태 변경(직접 SQL 등) |
   | RC-8 | 최근 24시간 이력의 확정 전이(→ `APPROVED`·`DECLINED`·`REFUNDED`)에 대응하는 Outbox 행이 없음 | 이벤트 없는 확정 |

   RC-7·RC-8은 Payment DB만으로 판단한다. 환불 행이 있으면(`FAILED` 포함) 의무가 추적 중이라 RC-2가 아니다.
5. **기록과 알림만**: 불일치는 `reconciliation_mismatch`에 `OPEN`으로 기록하고(같은 불일치는 행 하나를 이어 갱신), 다음 대사에서 맞으면 `RESOLVED`로 닫는다. 결제·예매 데이터는 고치지 않는다. `reconciliation_mismatch_open{rule}` Gauge가 0보다 크면 즉시 알림이고, 대사 작업의 마지막 성공이 15분을 넘으면 경고다([observability.md](../design/payment/observability.md)). 복구는 [manual-recovery.md](../design/payment/manual-recovery.md) 6절과 Ops 명령(0028)으로 한다.

## 검토한 대안

- **읽기 전용 DB 계정·뷰로 두 DB를 직접 조회**: 가장 빠르게 만든다. 서비스가 자기 DB를 소유한다는 경계를 깨고, 예매 스키마가 바뀌면 결제 쪽 쿼리가 조용히 깨진다. 사용자가 전제로 둔 "Reservation DB를 직접 읽지 못한다"와도 맞지 않는다.
- **이벤트 기반 사본**: Reservation이 예매 상태 변경 이벤트를 새 토픽으로 내고 Payment가 사본 테이블을 만든다. 경계는 지키지만 Reservation이 모든 전이에 Outbox를 써야 해 오픈 피크 부하가 늘고, 이벤트 유실을 찾는 도구가 다시 이벤트에 의존한다.
- **Reservation이 주체**: Reservation이 Payment 내부 API를 불러 대조한다. 돈의 원장이 아닌 쪽이 금전 불일치를 책임지게 되고, Payment에 외부 조회 API가 생긴다.
- **대사 없이 지표만**: 작업이 없다. RC-2·RC-4처럼 서비스 사이에서 생긴 어긋남을 찾지 못한다.

## 트레이드오프

| | 얻는 것 | 감수하는 것 |
|---|---|---|
| 내부 조회 API | 각 서비스가 자기 데이터를 소유한 채 대사한다. 계약이 명시적이다 | Reservation에 API 두 개가 생긴다(반효승 작업). 내부망 신뢰에 기댄다 |
| Payment가 주체 | 돈 쪽 불일치를 돈의 원장이 책임진다 | Payment가 예매 서비스 가용성에 대사를 의존한다(정체는 알림으로 감지) |
| 기록과 알림만 | 대사 오류가 데이터를 망가뜨리지 않는다 | 복구는 사람이 한다 |
| 허용 지연 10분 | 정상 지연(Outbox 2초, 소비 재시도, DLQ 자동 복구)을 오탐하지 않는다 | 실제 불일치를 최대 L + 5분 늦게 안다 |

## 결과

- 반효승: 내부 조회 API 두 개(S-23). 이 결정은 반효승 합의 전까지 제안이다.
- `data-model.md`의 `reconciliation_mismatch`, `outbox`의 `INDEX(created_at)`, `payment_status_history`의 `target_type`.
- 0020의 잔여 위험("토픽 보관 기간 초과 시 대응 없음")을 RC-2가 맡는다.
- 테스트: N23, N30, FT-1~FT-5의 V3(테스트 프로필은 L을 5초로 줄이고 대사를 강제 실행).

## 면접에서 설명할 한 줄

각 장치가 자기 실패만 보는 한계를 메우려고 결제와 예매의 상태·금액·사용자·환불 여부를 주기적으로 대조하는 대사를 두었고, DB를 공유하지 않는 MSA 경계를 지키려고 예매 서비스의 읽기 전용 내부 API로 대조하되 MVP는 고치지 않고 기록과 알림만 하게 했다.
