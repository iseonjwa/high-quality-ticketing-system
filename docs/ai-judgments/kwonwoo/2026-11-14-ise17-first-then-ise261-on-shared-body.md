---
title: ISE-17(접수형 소비자)을 먼저 머지하고 ISE-261 변경을 공용 선점 본체 위에서 다시 맞추기로 함
date: 2026-11-14
updated: 2026-11-14
judge: 최권우
ai: Claude Code (PR #609 코드와 ISE-261 미커밋 변경 비교 검토)
verdict: 수용
area: 동시성
presentation: 아님
shared: 아니오
---

# ISE-17(접수형 소비자)을 먼저 머지하고 ISE-261 변경을 공용 선점 본체 위에서 다시 맞추기로 함

## 맥락
- 해결하려던 작업: ISE-17(PR #609)이 동기형 선점 본체를 `SeatHolding`으로 떼어 냈고, ISE-261(미커밋)은 같은 본체를 ADR 0061·0062에 따라 고쳤다. 두 작업이 `SyncSeatHoldService`에서 충돌한다.
- AI 활용 방식: PR #609 머지 전에 더 검증할 부분이 있는지 묻고, 나온 확인 항목의 해결 방안을 트레이드오프와 함께 설명해 달라고 했다.

## AI 제안
[학습 노트](../../learning/kwonwoo/qna/2026-11-14-ise17-merge-verification-tradeoffs.md)
- 머지 순서: ISE-17 먼저. ISE-261의 본체 변경(약 50줄)이 #609의 본체 분리(main 14개 파일)보다 작아, 작은 쪽을 큰 쪽 위로 옮기는 편이 안전하다. ISE-261은 미커밋이라 열린 PR을 다시 쓰지 않는다.
- ISE-261의 사전 거절과 순서 변경을 공용 `SeatHolding`에 넣어 소비자에도 적용한다. 사전 거절은 오픈 순간 접수 검사를 모두 통과한 진 메시지를 소비자에서 싸게 거른다. 순서 변경은 본체를 두 벌로 나누지 않으려는 것이다.
- 소비자 잠금 시한을 100ms에서 50ms로 맞춘다(설정 키는 유지).

## 판단
- 결과: 수용
- 판단자: 최권우
- 근거: 미기재. "ISE-17을 먼저 최신화한 후 머지하고, ISE-261에 대한 내용을 수행하자"고 했다.
- 검토한 대안: ISE-261을 먼저 머지하고 #609에서 본체를 다시 떼어 내는 안

## 반영
- PR #609: `origin/dev`(ca40b631) 병합 후 squash 머지(dev `0f69c1aa`). Linear ISE-17 Done, 체크리스트 갱신
- ISE-261(미커밋, 머지된 dev 위로 옮김)
  - `SeatHolding`: 매수 → 예매·이력 → 요청 HELD → 좌석 UPDATE → 예매 좌석 순서. 요청 HELD 표시를 본체 안으로 옮김
  - `SyncSeatHoldService`: 잠금 시한 50ms, 읽은 좌석 상태로 사전 거절
  - `SeatHoldRequestProcessor`: 사전 거절을 같은 트랜잭션에서 닫음, 잠금 시한 기본값 50ms
  - `config-repo/reservation-service.yml`, `contracts.md` 2-2, ADR 0061·0062에 소비자 적용을 적음

## 검증
- PR #609 재병합 뒤: reservation-service 테스트 499건(건너뜀 14) 실패 0, `SeatHoldKafkaFlowTest` 3건 통과, CI `build`·`records-check` 통과
- ISE-261 반영 뒤: reservation-service 테스트 507건(건너뜀 17) 실패 0. `SEATHOLD_CONCURRENCY=true`로 `SeatHoldConcurrencyTest` 7건, `SeatHoldLockWaitTest` 2건, `SeatHoldKafkaFlowTest` 3건 통과
- C2 성공 82건(ADR 0061의 50ms 범위 78~92건 안), C3 최대 237ms(ADR 0061 기록 171~225ms보다 조금 길지만 테스트 목표 400ms 안). 1회 실행이다

## 판단 이력
- 2026-11-14 수용: ISE-17 먼저 머지, ISE-261은 공용 본체 위에서 다시 맞춤

## 발표·면접 포인트
- 두 작업이 같은 코드를 다르게 고쳤을 때, 다시 맞추는 양이 작은 쪽을 큰 쪽 위로 옮기고 본체는 하나로 유지했다.
