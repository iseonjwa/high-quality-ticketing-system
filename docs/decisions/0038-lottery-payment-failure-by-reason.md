# 0038. 추첨 자동결제 실패는 사유별로 나눠, NOT_SENT만 새 예매로 1회 재시도하고 결과 미수신 만료는 같은 예매로 1회 연장한다

- 상태: 제안 (추첨 담당 결정, 결제 담당 확인 대기, ISE-89)
- 날짜: 2026-10-31
- 결정자: 이강민 (추첨 담당)
- 관련 AI 판단: [결제 실패 처리](../ai-judgments/kangmin/2026-10-30-lottery-payment-failure-handling.md), [선반영 후 보고](../ai-judgments/kangmin/2026-10-31-lottery-sa-sync-apply-first-report-later.md)
- 상세: [`docs/design/lottery/options.md`](../design/lottery/options.md) D12, 학습 노트 [`2026-10-30-lottery-payment-failure-options.md`](../learning/kangmin/qna/2026-10-30-lottery-payment-failure-options.md)

## 맥락
- 결제는 예매당 1개다(`UNIQUE(reservation_id)`, [0026](0026-payment-one-per-reservation.md)). 0026은 "U9가 같은 예매 재결제를 요구하면 다시 연다"는 조건을 달았다.
- 결제 계약(`contracts.md`)은 "Reservation은 사유별로 분기하지 않아도 된다"고 적었다. 추첨은 우리 서버 장애로 당첨자가 자리를 잃지 않게 사유를 구분해야 한다.

## 결정
| 사유 | 처리 |
|---|---|
| `CARD_DECLINED`, `INSUFFICIENT_LIMIT`, 빌링키 없음, `OTHER` | 응모 FAILED, 다음 예비자 승계 |
| `NOT_SENT` | 같은 당첨자에게 새 예매로 1회 재시도(`payment_attempts` 1 → 2). 2회째도 실패하면 승계 |
| 결과를 받지 못한 채 선점 만료 | 새 예매를 만들지 않고 같은 예매의 선점을 1회 30분 연장(상한: 승계 마감 + 30분). 상한 뒤에는 FAILED + 승계, 그 뒤 승인은 E8 환불 |

- 두 경우를 다르게 다루는 이유: 결제 멱등은 `reservationId` 단위다. 요청이 아직 살아 있을 수 있는 결과 미수신은 같은 키를 유지해야 이중 결제를 막는다. `NOT_SENT`는 Payment가 망취소로 늦은 승인을 막고 닫은 거절이라 새 키로 재시도해도 안전하다.
- ADR 0026은 그대로 둔다. 같은 예매 재결제가 필요 없다.
- 결제 `contracts.md`에 "추첨 예매는 `reasonCode`로 분기한다"와 `NOT_SENT` 보장 문구를 더한다(10/31 선반영).

## 검토한 대안
- **사유와 관계없이 모두 승계**: 가장 단순하다. 우리 서버 장애로 당첨자가 자리를 잃는다.
- **같은 예매로 재결제**: 같은 좌석을 지킨다. 0026을 다시 열고 Payment와 PaymentAttempt를 나눠야 한다.
- **만료 시 원인 확인 후 분기**: 가장 정확하다. Reservation → Payment 동기 조회와 `reservationId` 기준 조회 API가 새로 필요하다.

## 결과
- 얻는 것: 우리 책임 실패에서 당첨자가 자리를 지킨다. 결제 모델은 바뀌지 않는다.
- 감수하는 것: lottery가 `reasonCode`로 분기한다. 재시도 예매는 다른 좌석을 받을 수 있다. 결과 이벤트가 유실된 승인은 상한 뒤 FAILED가 되고 결제 대사가 환불로 정리한다.
- 확인할 것(결제 담당): `NOT_SENT`의 보장 범위(이 주문번호로는 이후 승인이 없다), 빌링키 없음의 `reasonCode`.
- 바꿀 조건: 재시도 1회로도 우리 책임 실패가 남으면 횟수를 늘린다. 장애 주입에서 연장 상한 뒤 자리를 잃는 당첨자가 의미 있게 나오면 원인 확인 후 분기를 검토한다.
