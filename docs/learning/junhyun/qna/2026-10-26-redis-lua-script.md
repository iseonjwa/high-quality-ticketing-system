---
title: Redis Lua 스크립트는 무엇이고 왜 쓰는가
date: 2026-10-26
updated: 2026-10-26
asker: Junhyun An
level: 중급
tags: [redis, lua, atomicity, concurrency]
---

# Redis Lua 스크립트는 무엇이고 왜 쓰는가

## 질문
lua script가 뭔지 알려줘.

## 짧은 답
Redis 서버 안에서 실행되는 Lua 코드 조각이다. 여러 Redis 명령을 한 스크립트로 묶어 서버로 보내면 Redis가 그 스크립트를 끝까지 실행하는 동안 다른 클라이언트의 명령을 끼워 넣지 않는다. 그래서 "확인하고, 조건이 맞으면 쓰기" 같은 여러 단계 작업이 하나의 원자적 연산이 된다. 티켓팅에서는 여러 좌석을 "전부 잡거나 아무것도 안 잡는" 선점에 쓴다.

## 자세히
### 왜 필요한가
Redis 명령 하나(`SET`, `INCR`)는 원자적이지만, 명령 두 개 이상을 클라이언트에서 이어 보내면 그 사이에 다른 클라이언트가 끼어들 수 있다.

```text
클라이언트 A: EXISTS seat:A1  -> 0 (비어 있음)
클라이언트 B: EXISTS seat:A1  -> 0 (A가 아직 안 잡아서 B도 비어 있다고 봄)
클라이언트 A: SET seat:A1 A
클라이언트 B: SET seat:A1 B   -> 같은 좌석이 둘에게 잡힘 (경합 조건)
```

### 해결 방법 비교
| 방법 | 특징 |
|---|---|
| `SET key val NX EX ttl` | 키 하나에는 충분하다. 여러 키를 묶어서 판단할 수 없다 |
| `WATCH` + `MULTI/EXEC` | 낙관적 락. 충돌하면 재시도해야 하고 폭주 시 재시도가 몰린다 |
| Lua 스크립트(`EVAL`) | 조건 판단과 쓰기를 서버에서 한 번에 처리한다. 왕복도 1회다 |
| 분산 락(Redisson 등) | 락 획득·해제 왕복이 추가되고 락 만료 같은 별도 위험이 있다 |

### 동작 원리
- Redis는 명령을 처리하는 메인 스레드가 하나다(싱글 스레드 이벤트 루프). 스크립트도 이 스레드에서 한 덩어리로 실행되므로 중간에 다른 명령이 실행되지 않는다.
- 스크립트가 받는 입력은 `KEYS`(건드릴 키 목록)와 `ARGV`(그 밖의 인자)로 나눈다. Redis Cluster에서는 `KEYS`가 모두 같은 슬롯에 있어야 한다.
- `EVAL`은 스크립트 본문을 매번 보낸다. `SCRIPT LOAD`로 등록하고 `EVALSHA`로 해시만 보내면 전송량이 줄어든다.

```lua
-- KEYS: 좌석 키 목록, ARGV[1]: holdId, ARGV[2]: TTL(초)
for _, key in ipairs(KEYS) do
  if redis.call('EXISTS', key) == 1 then return 0 end  -- 하나라도 차 있으면 실패
end
for _, key in ipairs(KEYS) do
  redis.call('SET', key, ARGV[1], 'EX', ARGV[2])        -- 전부 잡기
end
return 1
```

### 주의할 점
- 스크립트가 길거나 느리면 그동안 Redis 전체가 멈춘다. 짧게 유지하고 반복문·큰 키 순회를 피한다.
- 스크립트 안에서 외부 I/O나 시간·난수에 기대는 로직은 쓰지 않는다. 입력(`KEYS`, `ARGV`)만으로 결과가 정해지게 쓴다.
- 원자성은 Redis 안에서만 보장한다. Redis와 DB 사이의 일관성은 별도 장치(DB 조건부 UPDATE 등)가 필요하다.
- 버전에 따라 Redis 7의 Functions(`FUNCTION LOAD`)가 스크립트 관리를 대체하는 방향이다. 기본 개념은 같다.

## 이 프로젝트에서는
- 좌석 선점: 여러 좌석을 원자적으로 잡는 스크립트. `docs/sa-review/03-seat-hold-kafka-design.md`의 "② Redis Lua가 원자적인 이유".
- 대기열 입장 속도: 인스턴스가 여러 개여도 입장 속도를 전역 카운터로 제어. `docs/sa-review/01-sa-1st-review.md` 49행 근처.
- Redis 판정이 틀릴 수 있어(재시작으로 키 유실 등) DB 조건부 UPDATE를 2차 방어로 둔다.

## 면접 포인트
- Redis 명령이 원자적인데 왜 Lua가 필요한가: 단일 명령만 원자적이고, 여러 명령 사이에는 다른 클라이언트가 끼어들 수 있기 때문이다.
- Lua와 `WATCH/MULTI`, 분산 락의 차이: 재시도 없이 한 번의 왕복으로 끝나는 점, 락 만료 문제가 없는 점.
- Lua의 단점: 느린 스크립트가 Redis 전체를 막고, 클러스터에서는 키를 같은 슬롯에 둬야 한다.

## 참고
- Redis 공식 문서: Scripting with Lua(EVAL), Redis functions
- `docs/sa-review/03-seat-hold-kafka-design.md`
