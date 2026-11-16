---
title: ISE-18 직접 발행을 커밋 뒤 전송·실패해도 202로 정하고, 측정 기준 코드에 ISE-261을 병합하기로 함
date: 2026-11-16
updated: 2026-11-16
judge: 최권우
ai: Claude Code (선택지 제시)
verdict: 수용
area: 이벤트 신뢰성
presentation: 후보
shared: 예
---

# ISE-18 직접 발행을 커밋 뒤 전송·실패해도 202로 정하고, 측정 기준 코드에 ISE-261을 병합하기로 함

## 맥락
- 해결하려던 작업: ISE-18(동기형·접수형 직접 발행·접수형 Outbox 세 방식 재측정)의 측정 전용 직접 발행 경로 구현. 재측정 계획(`docs/design/reservation/async-seathold-remeasure-plan.md` 3-1절)이 구현 전에 정할 것 두 가지를 남겨 두었다.
- AI 활용 방식: 사용자가 "ISE-18 내용을 구현해 달라"고 했고, AI가 정할 것 세 가지를 선택지와 추천으로 물었다.

## AI 제안
1. 보내는 시점
   - 커밋 뒤 전송(추천): 소비자가 커밋 전 행을 못 찾는 일이 없다. 전송이 실패하면 REQUESTED 행이 남고 30초 시한 정리가 닫는다.
   - 커밋 전 동기 전송: 롤백된 접수의 메시지가 남거나, 소비자가 커밋 전 행을 못 찾는 경우를 소비자에서 따로 다뤄야 한다.
2. 실패 응답
   - 브로커 확인을 최대 1초 기다리고 실패해도 202(추천): 행이 커밋돼 같은 키 재요청도 202 재응답이라 503이 사용자에게 주는 것이 없다. 실패는 지표로 센다.
   - 확인 기다림, 실패 시 503(SA 10-1 응모와 같은 방식)
   - 확인 기다리지 않음(가장 빠르지만 실패를 응답 시점에 모른다)
3. 측정 기준 코드
   - ISE-261 병합(추천): 접수형에도 해당하는 변경(한 문장 사전 판정, 소비자 잠금 시한 50ms, 상한 구조)으로 잰다. ISE-261 PR이 먼저 머지돼야 한다.
   - dev만: PR은 독립적이지만 ISE-261 머지 뒤 다시 재야 할 수 있다.

## 판단
- 결과: 수용(세 가지 모두 추천안)
- 판단자: 최권우
- 근거: 미기재
- 검토한 대안: 위 선택지

## 반영
- 코드: `AsyncSeatHoldService`(직접 발행 분기, 상한 자리는 접수 트랜잭션에서만), `SeatHoldDirectPublisher`, `KafkaSeatHoldDirectPublisher`
- 설정: `seat-hold.async.publish`(기본 `outbox`), `seat-hold.async.direct-publish-timeout`(기본 1s). config-repo는 바꾸지 않았다. 측정 덮어쓰기 `loadtest/k6/compose.seat-hold-async.yml`, `compose.seat-hold-direct.yml`
- k6: `reservation-service/k6/k1-seathold.js` 접수형 폴링 분기
- 문서: 재측정 계획 1·3절, ADR 0057 후속 작업 줄, `seathold-metrics.md` 1-4절, k6 README 4절
- 브랜치: `test/ISE-18-async-seathold-remeasure`에 dev와 ISE-261 병합

## 검증
- reservation-service 테스트: 새 테스트 `AsyncSeatHoldDirectPublishApiTest` 3건, `KafkaSeatHoldDirectPublisherTest` 3건 통과
- 실제 Kafka(로컬): `SeatHoldDirectPublishKafkaFlowTest` 1건(릴레이를 끄고 HELD·예매 좌석까지), 기존 `SeatHoldKafkaFlowTest` 3건 통과
- k6: 모의 서버로 폴링·재시도·집계 확인. 실제 서버 측정은 아직이다

## 판단 이력
- 2026-11-16 수용: 커밋 뒤 전송, 실패해도 202, ISE-261 병합
