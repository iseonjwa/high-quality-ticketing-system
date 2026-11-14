---
title: Kafka 소비자·스케줄러를 infrastructure에 두자는 추천을 거부하고 presentation으로 모두 옮기기로 팀이 정했다
date: 2026-11-03
updated: 2026-11-14
judge: 팀 회의 (전달: 이강민)
ai: Claude Code(이강민 세션) + Claude(대기열 담당 세션) 교차 의견
verdict: 거부
area: 계층형 아키텍처
presentation: 후보
shared: 예
---

# Kafka 소비자·스케줄러를 infrastructure에 두자는 추천을 거부하고 presentation으로 모두 옮기기로 팀이 정했다

## 맥락
- 해결하려던 작업: 대기열 담당이 Kafka 소비자를 만들면서 SA 13장 표("infrastructure → domain 인터페이스 구현")와 달리 infrastructure가 application을 호출하게 된다고 물었다.
- AI 활용 방식: 이강민이 Slack 대화를 Claude Code에 붙여 해석과 답장 초안을 요청했다. 대기열 담당도 자기 Claude에 같은 문제를 물어 답을 Slack에 공유했다.

## AI 제안
- Claude Code(이강민 세션): SA 표와 표 아래 문장("구독: 인프라 Consumer → application 서비스 호출")이 서로 다르게 읽히는 문서 문제로 봤다. 좌석 선점(`SeatHoldExpiryScheduler`), 추첨(`Lottery*Scheduler` 4개), 결제(`PaymentCommandListener`·`PaymentCommandDlqListener`·`RecoveryScheduler`)가 이미 infrastructure에서 application을 호출한다. 그래서 위치는 그대로 두고 SA 표에 "들어오는 쪽은 application 호출"을 보태는 안(A)을 추천했다. 대기열만 옮기면 모듈마다 위치가 달라져 가장 나쁘다고 했다.
- Claude(대기열 담당 세션): 소비자가 하는 일(파싱하고 application 호출)과 소비자를 둘 폴더는 서로 다른 질문이다. 이강민이 설명한 장점(유스케이스 공유, Kafka 없이 테스트, 장애 처리 격리)은 `presentation/messaging`으로 옮겨도 그대로 남는다. 옮기면 의존 방향이 표 규칙과 맞는다. 남는 비용은 기존 코드 이동과 SA 13장 수정이다. 소비자·스케줄러 같은 입구를 presentation으로 옮기자고 제안했다.

## 판단
- 결과: 거부(Claude Code의 A안). 2026-11-03 팀 회의에서 B안(presentation으로 이동)으로 확정했다.
- 판단자: 이강민(1차), 팀 회의(확정, 전달: 이강민)
- 근거: 1차는 "인정합니다". 회의 결론은 "4계층 구조에 더 적합한 구성이며, 이후 혼란을 막을 수 있을 것 같아 선택함". 감수한 점은 클래스 참조와 테스트 경로를 빠뜨리지 않고 고쳐야 한다는 것이다.
- 검토한 대안: A안(위치 유지 + SA 표 보강), 대기열만 이동

## 반영
- [ADR 0043](../../decisions/0043-inbound-adapters-in-presentation.md)과 SA 13장 계층 표(ISE-165)
- 입구 클래스 이동은 ISE-166. 대기열 담당은 대기열 컨슈머를 `presentation/messaging`에 구현한다.

## 검증
구현 전

11/14 보강: ISE-166 구현은 [PR `#272`](https://github.com/iseonjwa/high-quality-ticketing-system/pull/272)로 2026-11-03 15:36 KST에 머지됐다. 아래 숫자는 모두 PR `#272` 본문 "결과" 표에서 옮겼다.
- `./gradlew test --continue`: 이동 전(dev `5dd9f26`) 1355개 실패 0, 이동 후 1355개 실패 1. 실패 1건은 이동과 무관한 `RefundWorkerQueryTest.임대는_pg_requested_at을_처음만_채우고_이후에는_유지한다`의 1ms 차이이고, 단독으로 3번 다시 돌려 모두 통과했다(PR 본문은 `Instant.now()` 나노초가 PostgreSQL 마이크로초로 반올림돼 생기는 간헐 실패로 봤다).
- 바뀐 줄은 `package`·`import` 선언과 주석의 패키지 이름뿐이다(파일 내용 diff 22줄).
- `@KafkaListener`·`@Scheduled`가 infrastructure에 남은 클래스는 범위 밖 3개(`BackgroundJobScheduler`, `LocalMessagingProbe`, `CachedScheduleReader`)뿐이다.

## 판단 이력
- 2026-11-03 거부: Claude Code의 위치 유지안 대신 입구 어댑터를 presentation으로 모두 옮기는 안을 지지하고 팀에 물음
- 2026-11-03 확정: 팀 회의에서 B안(presentation 이동)으로 결정
- 2026-11-14 검증 보강: PR `#272`(ISE-166) 머지와 이동 전후 테스트 1355개 결과를 검증 칸에 더함

## 발표·면접 포인트
- "무슨 일을 하나"와 "어느 폴더에 두나"를 나눠 보니, 소비자가 application을 부르는 역할은 그대로 두고 위치만 바꿔 의존 방향 규칙을 지킬 수 있었다.
- 두 AI의 의견이 갈렸을 때 기존 코드와 맞추는 쪽 대신 규칙과 맞추는 쪽을 골랐다. 대신 기존 코드 이동 비용은 감수한다.
