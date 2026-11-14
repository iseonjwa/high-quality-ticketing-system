---
title: 추첨 자동결제의 우리 책임 실패는 NOT_SENT면 새 예매로 1회 재시도, 결과 미수신이면 같은 예매로 1회 연장하기로 함
date: 2026-10-30
updated: 2026-11-14
judge: 이강민
ai: Claude Code (추첨 설계 문서 D12 추천, NOT_SENT 발생 경로와 처리 선택지 비교)
verdict: 수용
area: 추첨
presentation: 후보
shared: 예
---

# 추첨 자동결제의 우리 책임 실패는 NOT_SENT면 새 예매로 1회 재시도, 결과 미수신이면 같은 예매로 1회 연장하기로 함

## 맥락
- 해결하려던 작업: ISE-49 추첨 상세 설계의 결정 D12(결제 실패 사유별 처리, SA 미결정 U9) 검토. 설계 문서는 `docs/design/lottery/options.md` D12이다.
- AI 활용 방식: `NOT_SENT`가 어떤 경우에 생기는지, 결과 미수신 만료를 다르게 처리할 선택지가 있는지 물었다(학습 노트 `docs/learning/kangmin/qna/2026-10-30-lottery-payment-failure-options.md`).

## AI 제안
- `NOT_SENT`: Payment가 REQUESTED를 커밋한 뒤 요청이 PG에 닿지 않은 경우(PG 호출 전 서버 종료, 네트워크 유실, 망취소 뒤 늦은 도착)다. PG "주문 없음" 확인 → 망취소 성공 뒤에만 닫으므로 이후 승인이 없다는 보장이 있다. 선택지: 새 예매 1회 재시도(추천), 즉시 승계, 같은 예매 재결제(ADR 0026 변경).
- 결과 미수신 만료: 요청이 살아 있을 수 있어 새 키를 쓰면 이중 결제 위험. 선택지: 같은 예매 1회 연장(추천), 즉시 승계, 원인 확인 후 분기(미발행 outbox 취소, `reservationId`로 Payment 조회), 연장 반복, 만료 없음.
- 기준: 같은 멱등 키 재시도는 언제나 안전하고, 키를 바꾸는 재시도는 옛 요청이 확실히 죽었을 때만 안전하다.

## 판단
- 결과: 수용
- 판단자: 이강민
- 근거: AI 제안의 근거(멱등 키 기준, 원인 확인 분기는 드문 장애에서만 차이가 나고 동기 호출·새 API 비용이 큼)를 받아들였다. 사용자 발언은 "A로 결정"이다.
- 검토한 대안: 위 선택지 전부.
- 감수: 결과 이벤트가 유실되고 연장 상한이 지나면 당첨자는 자리를 잃고 대사(RC-2)가 환불로 정리한다. lottery가 `reasonCode`로 분기해 결제 계약 문구를 고쳐야 한다.

## 반영
- `docs/design/lottery/options.md` D12 결정·추가 바꿀 조건, `README.md` 상태 줄·결정 목록·이슈화 후보(장애 주입)·일괄 반영 대기(결제 `contracts.md`).
- 결제 담당 합의: `NOT_SENT` 보장 범위, 계약 문구, 빌링키 없음 `reasonCode`.

## 검증
구현 전. 장애 주입: PG 호출 전 결제 서버 종료 → `NOT_SENT` → 새 예매 재시도 1회, outbox 릴레이 정지 → 선점 1회 연장 → 복구 뒤 결제 1회.

11/14 보강: 두 처리는 구현돼 로컬 테스트와 2026-11-11 배포 서버 F1에서 확인했다. 숫자는 [`docs/design/lottery/f1-fault-injection.md`](../../design/lottery/f1-fault-injection.md) 7절 "시각 기록표"와 9절에서 옮겼다.
- `NOT_SENT` 새 예매 재시도: 대체 통합 테스트 4개(결제 `Ft3RestartScenarioTest.a_*`, 예매 `LotteryScenarioTest.S3_*`·`S13_*`, `PaymentResultLotteryTest.NOT_SENT_*`)가 2026-11-11 15:15 KST 로컬에서 모두 통과했다(실패 0, 9절). 서버 F1-a(결제 서버 `docker kill`, 16:25:05.282)에서는 `NOT_SENT`가 0건이라 서버에서는 이 경로를 보이지 못했다. 그때 결과는 결제 `APPROVED` 17, `DECLINED(BILLING_KEY_MISSING)` 2, `REQUESTED`·`UNKNOWN`·`NEEDS_MANUAL_CHECK` 0, 원장 승인 34 = 결제 DB `APPROVED` 34, 중복 주문 0이다(7절 F1-a 칸).
- 결과 미수신 1회 연장: F1-b(Kafka 정지, 16:32:47.307)에서 선점 만료를 1분 뒤로 앞당긴 예매 4건이 만료 3초 뒤 모두 1회 연장됐다. `hold_expires_at`은 앞당긴 값 + 30분, 예매 `HELD`·응모 `WON` 유지다(7절 F1-b 칸).
- 복구 뒤 결제 1회: F1-c(Kafka 재기동, 16:46:14.034)에서 밀린 outbox 4건이 발행되고 예매당 결제 1건이었다. 원장 승인 38 = 결제 DB `APPROVED` 38, 중복 주문 0, `dead_letters` 0/0이다(7절 F1-c 칸).
- 연장 상한이 지난 뒤 대사(RC-2)가 환불로 정리하는 경로는 F1 범위 밖이라 이 보강에서는 확인 못 함. ADR 0038의 상태 줄은 아직 "제안(결제 담당 확인 대기)"이다([ADR 0038](../../decisions/0038-lottery-payment-failure-by-reason.md)).

## 판단 이력
- 2026-10-30 수용: 재시도 1회 + 같은 예매 1회 연장. 장애 주입에서 손실이 크면 원인 확인 분기 검토.
- 2026-11-14 검증 보강: 로컬 대체 테스트 4개와 11/11 서버 F1 결과(`f1-fault-injection.md` 7·9절)를 검증 칸에 더함

## 발표·면접 포인트
- 같은 "우리 책임 실패"라도 옛 요청이 확실히 죽었는지에 따라 멱등 키를 바꿀지 말지를 나눴다. 확실하면(`NOT_SENT`) 새 키로 재시도, 모르면(결과 미수신) 같은 키로 기다리고 상한 뒤는 대사가 정리한다.
