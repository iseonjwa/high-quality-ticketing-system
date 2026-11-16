---
title: 대기열 지표를 입장 처리 Lua에 얹고 회차별 라벨로 내보내자는 AI 첫 설계를 되물어, 읽기 전용 Lua와 회차 합계 게이지로 바꿨다
date: 2026-11-14
updated: 2026-11-14
judge: youngeun
ai: Claude (Cowork, 설계 비교와 테스트 작성)
verdict: 부분 수용
area: 대기열
presentation: 후보
shared: 예
---

# 대기열 지표를 입장 처리 Lua에 얹고 회차별 라벨로 내보내자는 AI 첫 설계를 되물어, 읽기 전용 Lua와 회차 합계 게이지로 바꿨다

## 맥락
- 해결하려던 작업: ISE-61 중 대기열 지표. 대기열 상태(대기 인원, 입장 번호, 좌석 화면 인원)는 Redis에만 있고 지표가 없어, 측정 때 대기열 흐름을 k6 결과로만 추정했다. 11/15 ISE-264 측정 전에 머지해야 한다.
- 팀 코드에서 업무 지표를 직접 만든 곳은 결제·추첨·Gateway·Outbox뿐이고, 대기열은 `queue-redis` 차단기 상태만 있었다.
- AI 활용 방식: 대기열 Lua·스케줄러, `LotteryMetrics`, Prometheus 수집 설정을 읽게 하고 지표 설계를 묻고, 테스트를 만들게 했다.

## AI 제안
첫 설계:
- `admit.lua`가 끝날 때 `admitted`·`seq`·대기 인원·`issued` 크기를 돌려주고, 저장소 `admit()`의 반환을 `void`에서 바꾼다. Redis 호출이 늘지 않는다.
- 회차 ID를 라벨로 붙인 게이지를 회차마다 등록하고, 판매가 끝난 회차는 게이지를 지운다.
- 처음 발급만 세는 토큰 발급 카운터를 두고, "입장 번호 − 발급 수"로 버려진 입장 자리를 계산한다. 처음 발급과 재발급을 나누려면 `issue-token.lua` 반환을 바꿔야 한다.

되물음 뒤 바꾼 설계:
- 입장 Lua는 그대로 두고, 읽기만 하는 `stats.lua`를 입장 처리 직후 따로 부른다(회차당 0.5초마다 1번, 초당 2번). 입장 로직·`admit()`·기존 테스트를 건드리지 않고, 같은 Lua를 바꾸는 유예 시간 작업과도 겹치지 않는다.
- `LotteryMetrics` 주석 "라벨에는 ID 없이 값의 종류가 정해진 항목만 쓴다"를 근거로, 회차 ID 라벨 없이 판매 중인 회차를 합친 값을 낸다. 회차별 등록·제거가 필요 없어진다.
- 토큰 발급 카운터 대신 `waiting`에 남은 차례 지난 사람(`ZCOUNT waiting -inf admitted`)을 게이지로 센다. 토큰을 받으면 `waiting`에서 빠지므로 남은 사람이 곧 발급되지 않은 사람이다. `issue-token.lua`를 바꾸지 않는다.
- 여러 인스턴스가 같은 Redis를 각자 읽어 같은 값을 내므로 대시보드에서는 `sum`이 아니라 `max`로 본다.
- 한 회차라도 읽기에 실패하면 이번에는 지표를 갱신하지 않는다. 일부만 더한 값으로 덮어쓰면 Redis 장애 동안 대기 인원이 0으로 떨어져 보인다.

## 판단
- 결과: 부분 수용
- 판단자: youngeun
- 근거: "admit.lua를 왜 고쳐? 처음부터 끝까지 다시 상세하게 알려줘." 입장 처리 Lua를 지표 때문에 바꿔야 하는지 되물었고, AI가 읽기 전용 Lua를 따로 두는 쪽이 더 단순하고 안전하다고 다시 설계했다. 회차 라벨 제거와 카운터 대신 게이지는 그 과정에서 AI가 팀 코드 규칙과 Redis 구조를 근거로 함께 바꿨다.
- 이름: AI가 제안한 `admittedUnclaimed`(찾아가지 않음) 대신 사용자 제안 `admittedUnissued`(발급되지 않음)로 했다. 코드가 `issued`·`issueToken`·`issue-token.lua`로 쓰는 말과 맞는다. 사용자가 제안한 `StatsOutcome`은 기존 `…Outcome`이 경우(`Type`)를 갖는 시도의 결과라 성격이 달라 `QueueStats`로 두었다.
- 실패 시 갱신 건너뛰기는 AI 추천을 받아들였다.
- 검토한 대안: `admit.lua`에 얹기(AI 첫 제안), 회차 ID 라벨, 처음 발급 카운터, 실패한 회차만 빼고 갱신

## 반영
브랜치 `feat/ISE-61-queue-metrics`.
- `resources/redis/queue/stats.lua`(새로, 읽기만): 줄 선 인원, 입장 번호, 차례 전 대기, 차례 지났는데 발급 안 됨, 유효 토큰
- domain: `QueueStats`(`plus`, `EMPTY`), `QueueRepository.stats()`
- infrastructure: `RedisQueueRepository.stats()`를 `call()`로 감싸 차단기·예외 변환을 거친다
- application: `QueueAdmissionService`가 회차별 `admit()` 뒤 `stats()`를 합치고, 실패가 없으면 끝에서 한 번 `update()`
- observability: `QueueMetrics`(새로). 게이지 `queue.joined`, `queue.admitted`, `queue.waiting`, `queue.admitted.unissued`, `queue.entry.tokens.active`

## 검증
- `QueueStatsTest`: 항목별 합, 원래 값 불변, `EMPTY`
- `QueueMetricsTest`: 처음 0, 넣은 값 그대로, 다시 넣으면 더하지 않고 바꿈
- `QueueAdmissionServiceTest`: 회차 둘의 합, 매번 새로 합침, 실패하면 이전 값 유지, 실패가 풀리면 다시 갱신, 판매 중 회차가 없으면 0
- `RedisQueueRepositoryTest` "통계"(실제 Redis): `admitted`번을 경계로 차례 전·후를 나눔(`admitted`번은 차례 후), 토큰을 받으면 발급 안 된 인원에서 빠짐, 만료 토큰은 안 셈, 읽기만 함, 다른 회차는 안 셈
- 로컬 `/actuator/prometheus`: 예전 진입 테스트 기록이 남은 판매 중 회차에서 `queue_joined` = `queue_admitted` = `queue_admitted_unissued` = 553, `queue_waiting` 0으로 숫자끼리 맞았다
- 구현 중 잡은 것: 게이지 이름에 필드 이름을 그대로 넣어 `queue_admitted_admittedUnissued`처럼 나올 뻔했다. Micrometer는 점만 밑줄로 바꾸고 대소문자는 그대로 둔다
- 미확인: 배포 서버 측정에서의 그래프(11/15)

## 판단 이력
- 2026-11-14 부분 수용: 입장 Lua에 얹는 첫 설계를 되물어 읽기 전용 Lua·회차 합계 게이지로 바꿈, 이름은 `admittedUnissued`

## 발표·면접 포인트
- 지표를 위해 핵심 로직(입장 처리 Lua)을 바꾸지 않았다. 읽기 전용 스크립트를 따로 두어 비용(초당 Redis 2번)과 위험을 바꿨다.
- 같은 값을 여러 인스턴스가 내보내는 게이지는 `sum`이 아니라 `max`로 봐야 한다. 서버마다 다른 요청을 세는 HTTP 지표와 다르다.
- 버려진 입장 자리를 카운터 계산 대신 Redis 상태(차례가 지났는데 `waiting`에 남은 사람)로 바로 셌다.
