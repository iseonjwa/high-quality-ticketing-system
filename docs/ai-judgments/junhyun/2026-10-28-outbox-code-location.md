---
title: Outbox·처리 이력 코드를 payment-service에 둔 AI 판단을 되돌려 common 모듈로 옮기기로 함
date: 2026-10-28
updated: 2026-10-28
judge: junhyun
ai: Claude Code + Opus 리뷰 에이전트(deep-reasoner)
verdict: 수용
area: 서비스 경계
presentation: 아님
shared: 예
---

# Outbox·처리 이력 코드를 payment-service에 둔 AI 판단을 되돌려 common 모듈로 옮기기로 함

## 맥락
- 해결하려던 작업: ISE-24(`payment.result` 발행과 Outbox·`processed_event`) 구현. 이슈와 SA 6-6은 공통 코드를 `common.messaging`·`common.event`에 두라고 했다.
- AI 활용 방식: 구현 에이전트가 이슈와 다르게 `payment-service`의 `event` 패키지에 두고 PR #79를 올렸다. Opus 리뷰 에이전트가 이것이 SA 6-6과 다르며 팀 합의를 확인 없이 바꾼 것이라고 지적했다.

## AI 제안
- 지금 쓰는 서비스가 결제뿐이고 Reservation은 다른 토픽을 쓰므로, 예상 재사용만으로 범용화하지 않고 `payment-service`에 두자는 안(문서에 한 문단으로 기록).
- 리뷰 후 선택지: (1) `payment-service`에 두고 ADR 기록, (2) `common`으로 이동, (3) 일반 부품은 `common`, 결제 전용은 `payment`로 분리.

## 판단
- 결과: 수용. 코드를 `common` 모듈로 옮긴다. 트레이드오프를 설명받고 AI 추천대로 `common`의 `messaging` 패키지에 두고 일반 부품과 결제 전용 부품은 나누기로 했다. 별도 모듈(`common-messaging`)은 필요해지면 뺀다.
- 판단자: junhyun
- 근거: "common 모듈로 옮기자 일단. 나누는 것도 고려하고싶긴한데... 어떤 이점이 있는지 트레이드오프는 뭔지 자세히좀 알려줄래?" 설명을 들은 뒤 "오케이 좋아 추천대로 해줘!"
- 검토한 대안: 위 세 가지, 그리고 별도 Gradle 모듈 `common-messaging`([질문 노트](../../learning/junhyun/qna/2026-10-28-common-messaging-placement.md)).

## 반영
- PR #79에 반영 중: `common.messaging`에 Outbox·처리 이력, `payment-service`에 결제 이벤트와 발행기.

## 검증
- 구현 전

## 판단 이력
- 2026-10-28 부분 수용: `common`으로 옮기기로 했고 분리 여부는 보류.
- 2026-10-28 수용: 설명 뒤 추천대로 `common` 패키지 + 일반/결제 전용 분리로 확정.

## 발표·면접 포인트
- 이슈·SA와 다르게 구현해야 하면 확인을 받고 기록한다. 에이전트가 이를 어겼고 리뷰가 잡아냈다.
