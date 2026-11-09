---
title: 결제 건별 슬랙 알림(ISE-183)의 세부 여섯 가지를 AI 추천안대로 정함
date: 2026-11-09
updated: 2026-11-09
judge: Junhyun An
ai: Claude Code
verdict: 수용
area: 결제
presentation: 아님
shared: 예
---

# 결제 건별 슬랙 알림(ISE-183)의 세부 여섯 가지를 AI 추천안대로 정함

## 맥락
- 해결하려던 작업: 슬랙 알림에 "몇 번 유저가 언제 결제한 건인지"를 담는다. 11/4에 Loki 로그 알림으로 정했고(ADR 0045 결정 4), 11/9에 구현 전 세부를 물었다.
- AI 활용 방식: 구현 전 조사 결과와 세부 선택지마다 추천안을 받았다.

## AI 제안
Loki 규칙 → Alertmanager → 슬랙 경로에서 아래 여섯 가지를 추천했다.
1. 결제 시각은 `paidAt` = `payments.created_at`을 Asia/Seoul로 바꾼 ISO-8601(오프셋 포함, 초 단위). 시간대를 코드에 고정한다.
2. 기존 지표 알림(`PaymentNeedsManualCheck` 등)은 그대로 두고 건별 알림을 그 위에 더한다.
3. 금액 불일치(`PG_AMOUNT_MISMATCH`·`REFUND_AMOUNT_MISMATCH`)는 따로 알리지 않고 NMC 알림의 사유로 합친다. 진단 로그 줄은 남긴다.
4. 건별 알림은 해결 메시지를 보내지 않는다.
5. 대사 한 회차에서 규칙당 새 OPEN 건별 줄은 10건까지 남기고, 넘으면 요약 한 줄을 남긴다.
6. 환불 건 문구는 "N번 유저가 YYMMDD HH:MM에 결제한 건의 환불입니다"(원 결제 시각)로 한다.

## 판단
- 결과: 수용
- 판단자: Junhyun An
- 근거: 미기재. 추천안 채택으로 전달받았다.
- 검토한 대안: 세 안(Loki 로그 알림, 결제 서비스가 슬랙에 직접 전송, 말투만 바꾸고 상세는 링크) 중 Loki 로그 알림은 11/4에 이미 골랐다.

## 반영
[observability.md](../../design/infra/observability.md) 3절 "건별 알림"에 결정 표로 적었다. 구현은 ISE-183 브랜치(`feat/ISE-183-payment-event-alerts`)다.

## 검증
`PaymentAlertLogTest`(줄 형식), 결제·환불·대사·DLQ 통합 테스트, `script/check-loki-rules.sh`, `script/check-prometheus-rules.sh`로 확인했다. 결과는 PR에 적는다.

## 판단 이력
- 2026-11-09 수용: 여섯 가지를 추천안대로 정함.
