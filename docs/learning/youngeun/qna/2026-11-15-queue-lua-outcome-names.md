---
title: 대기열 Lua의 결과 이름(WAITING·READY·ADMITTED)은 무엇을 기준으로 붙이고, 토큰 API의 TOKEN_ISSUED는 왜 READY가 아닌가
date: 2026-11-15
updated: 2026-11-15
asker: kye
level: 기초
tags: [queue, redis, lua, naming, api-design]
---

# 대기열 Lua의 결과 이름은 무엇을 기준으로 붙이고, TOKEN_ISSUED는 왜 READY가 아닌가

## 질문
토큰 발급 Lua가 돌려주는 `TOKEN_ISSUED`를 진입·순번 조회처럼 `READY`로 바꾸면 안 되는가. `ADMITTED`라는 설명이 잘 이해되지 않는다.

## 짧은 답
결과 이름은 "Redis에 무엇이 저장돼 있나"가 아니라 "이 API를 부른 사람이 다음에 무엇을 하면 되나"를 기준으로 붙인다. `READY`는 "토큰 받으러 가세요"이고, 토큰 API의 성공 결과는 "토큰 여기 있습니다"다. 토큰 API 안에서 `READY`를 쓰면 이미 토큰을 줬는데 "받으러 가세요"라고 말하는 셈이 된다. 그래서 토큰 API의 결과는 `TOKEN_ISSUED`로 두거나, 세 상태 이름에 맞춰 `ADMITTED`로 바꾼다.

## 자세히
식당 대기에 빗대면 다음과 같다.

| 상태 | 식당 비유 | 대기열 API |
|---|---|---|
| `WAITING` | "아직 7팀 남았어요. 기다리세요" | 진입·순번 조회가 순번과 함께 응답 |
| `READY` | "차례 됐어요. 카운터에서 입장권 받아 가세요" | 진입·순번 조회가 응답. 프런트는 토큰 API를 부른다 |
| `ADMITTED` | "입장권 여기 있습니다" | 토큰 API가 응답. 입장 토큰을 준다 |

- 대기 화면(진입·순번 조회)이 하는 말은 `WAITING`이나 `READY` 둘 중 하나다.
- 카운터(토큰 API)가 하는 말은 "입장권 여기 있습니다"(`ADMITTED`), "아직 차례 아니에요"(409 `QUEUE_NOT_READY`), "명단에 없어요"(404 `QUEUE_NOT_JOINED`) 셋 중 하나다. 카운터는 "카운터로 가세요"(`READY`)라고 말할 일이 없다.

같은 사람이라도 어느 창구에 물었느냐에 따라 답이 다르다. 입장권을 이미 받은 사람이 대기 화면을 새로 열면 `READY`("카운터로 가세요")가 나온다. 그 사람이 카운터에 가면 같은 입장권을 다시 받는다(`ADMITTED`). 저장된 사실(`issued`에 기록 있음)은 같지만 창구마다 알려 줄 다음 행동이 달라서 이름도 다르다.

`TOKEN_ISSUED`와 `ADMITTED`는 같은 뜻이다. 앞의 것은 "무슨 일이 일어났나"(토큰을 발급함), 뒤의 것은 "그래서 지금 어떤 상태인가"(입장함)라는 관점의 이름이다. 진입·순번 조회 Lua가 `WAITING`·`READY`를 쓰고 토큰 Lua의 `NOT_READY`도 `WAITING`으로 바꾼다면, 성공 결과도 `ADMITTED`로 맞춰야 세 Lua가 같은 이름 체계를 쓴다.

## 이 프로젝트에서는
- 상태 정의: ADR 0031 결정 2, `AdmissionStatus`(READY·WAITING·ADMITTED).
- 결과 이름: `EnterOutcome`·`PositionOutcome`·`IssueTokenOutcome`과 `redis/queue/*.lua`. ISE-282에서 READY 판정을 Lua로 옮기며 이름을 맞추는 중이다.

## 면접 포인트
- 같은 데이터라도 API마다 응답 상태 이름이 다를 수 있다. 상태 이름은 저장 형태가 아니라 클라이언트가 할 다음 행동을 기준으로 정해야 프런트 분기가 한 가지 행동에 대응한다.

## 참고
- `docs/decisions/0031-queue-token-api-and-ready-status.md`
