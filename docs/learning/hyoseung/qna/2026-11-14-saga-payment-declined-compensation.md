---
title: 결제 거절 뒤 보상(좌석 원복)은 어떤 흐름이고 ISE-267 캡처는 무엇을 보여 주는가
date: 2026-11-14
asker: hyos1
level: 중급
tags: [saga, compensation, kafka, payment, reservation]
---

# 결제 거절 → 보상 흐름

## 질문

결제를 일부러 거절되게 만들고, 서비스 간 트랜잭션이 분리돼 원자적으로 수행되지 않으니 예약 → 결제 결과 → 예약 서비스가 실패를 보고 원복하는지 캡처하는 것인가. 어떻게 하나.

## 답

맞다. 두 가지만 다듬는다.

- 예매 서비스가 결제 결과를 호출해 받는 것이 아니라, 결제 서비스가 Kafka 이벤트 `payment.result`를 발행하고 예매 서비스가 소비한다(SAGA 코레오그래피, SA 14장 #3).
- 보상은 "예약 취소(CANCELED)"가 아니라 선점 해제다. `PaymentResultService.onDeclined`: 예매 `HELD → RELEASED`(이력 사유 `PAYMENT_DECLINED`), 좌석·1인 매수 한도 반환. CANCELED는 확정 예매를 사용자가 취소할 때다.

흐름: 결제 거절 → `payment.result`(거절) → 예매 HELD→RELEASED, 좌석 원복.

캡처(ISE-267): 사용자 화면 "결제가 거절됐어요", 요청 로그 패널 traceId(`?demo=on`), 그 traceId의 Grafana 요청 로그, 다시 선택 가능해진 배치도. 장애는 운영자 화면 `/admin/demo/faults`의 [결제 거절]로 걸고 끝나면 [모두 해제]. 측정 모드가 꺼져 있을 때, 팀에 알린 뒤 한다(`docs/guides/demo-scenarios.md`).

## 더 볼 것

- `docs/design/payment/failure-scenarios.md`, SA 8장
