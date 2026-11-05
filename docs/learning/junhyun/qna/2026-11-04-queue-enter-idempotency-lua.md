---
title: 대기열 진입 API는 멱등한가, Lua 스크립트가 멱등을 만들어 주는가
date: 2026-11-04
updated: 2026-11-04
asker: Junhyun An
level: 중급
tags: [redis, lua, idempotency, atomicity, queue]
---

# 대기열 진입 API는 멱등한가, Lua 스크립트가 멱등을 만들어 주는가

## 질문
대기열 진입도 멱등 처리해? Lua가 해준다던데...

## 짧은 답
멱등하다. 같은 사용자가 같은 회차 대기열에 진입 요청을 여러 번 보내도(연타, 새로고침, 응답 유실 뒤 재시도, 탭 두 개) 번호표는 한 번만 발급되고 이후 요청은 같은 번호를 돌려준다.
다만 "Lua가 멱등을 해 준다"는 말은 반만 맞다. Lua가 보장하는 것은 **원자성**(스크립트 실행 중 다른 명령이 끼어들지 않음)이다. 멱등은 Lua 안의 **"이미 줄에 서 있으면 그 번호를 돌려준다"는 확인 로직**이 만든다. 확인 로직은 같은 요청이 와도 다시 쓰지 않게 하고, Lua는 그 확인과 쓰기 사이에 다른 요청이 끼어들지 못하게 한다. 둘 중 하나만 있으면 멱등이 깨진다.

## 자세히

### 멱등이란
같은 요청을 한 번 보내든 여러 번 보내든 **서버에 남는 결과가 같다**는 성질이다. HTTP 명세(RFC 9110 9.2.2)도 멱등을 "여러 번 보낸 요청이 서버에 의도한 효과가 한 번 보낸 것과 같음"으로 정의한다. 응답 본문까지 똑같아야 한다는 뜻은 아니다.

POST는 HTTP 메서드 정의상 멱등이 아니다. 그래서 POST API를 멱등하게 만들려면 애플리케이션이 직접 "이 요청은 이미 처리했다"를 알아보는 장치를 둬야 한다.

### 멱등에 필요한 두 가지
1. **같은 요청을 알아보는 기준(키)**: 무엇을 "같은 요청"으로 볼지 정한다.
2. **확인과 쓰기가 한 덩어리로 실행됨(원자성)**: "이미 처리했나?"를 확인한 뒤 쓰는 사이에 다른 요청이 끼어들면 둘 다 "처음"이라고 판단한다.

### 하나만 있으면 어떻게 깨지나
대기열 진입은 "번호표 카운터를 1 올리고(INCR), 그 번호로 대기 줄(ZSET)에 사용자를 넣는다(ZADD)"는 작업이다.

**Lua만 있고 확인 로직이 없는 경우**: 스크립트가 `INCR` + `ZADD`만 한다.

```text
사용자 A 진입        -> seq 1, waiting = {A:1}
사용자 B 진입        -> seq 2, waiting = {A:1, B:2}
사용자 A 새로고침    -> seq 3, ZADD가 A의 점수를 3으로 덮어씀 -> waiting = {B:2, A:3}
```

Redis ZSET의 `ZADD`는 이미 있는 멤버면 점수를 덮어쓴다. A는 새로고침 한 번에 B 뒤로 밀리고 1번은 주인 없는 번호가 된다. 각 실행은 원자적이었지만 멱등하지 않다.

**확인 로직은 있는데 Lua 없이 명령을 따로 보내는 경우**: 애플리케이션이 `ZSCORE`로 확인하고, 없으면 `INCR`·`ZADD`를 보낸다.

```text
A의 요청1: ZSCORE A -> 없음
A의 요청2: ZSCORE A -> 없음   (요청1이 아직 ZADD 전이라 둘 다 "처음"으로 판단)
A의 요청1: INCR -> 1, ZADD A 1
A의 요청2: INCR -> 2, ZADD A 2  (덮어씀)
```

요청1의 응답은 "1번"이라고 했는데 실제 자리는 2번이고, 1번은 빈 번호가 된다. 버튼 연타나 탭 두 개면 실제로 일어나는 일이다. 이런 문제를 "확인 후 실행(check-then-act) 경합"이라고 한다.

**둘 다 있는 경우**: 확인과 쓰기가 한 Lua 스크립트 안에 있으면, 요청2는 요청1의 스크립트가 끝난 뒤에야 실행되고 `ZSCORE`에서 A를 찾아 기존 번호를 돌려준다.

### Lua가 원자적인 이유
Redis는 명령을 한 스레드에서 하나씩 실행한다. Lua 스크립트(`EVAL`/`EVALSHA`)는 하나의 명령으로 취급돼, 스크립트가 끝날 때까지 다른 클라이언트의 명령이 실행되지 않는다. 그래서 스크립트 안의 "확인 → 쓰기"가 한 덩어리가 된다. 대신 스크립트가 길면 그동안 Redis 전체가 멈추므로 짧게 유지해야 한다.

Lua에 대한 기초는 [Redis Lua 스크립트는 무엇이고 왜 쓰는가](2026-10-26-redis-lua-script.md)에 있다.

### 원자성은 롤백이 아니다
Lua 스크립트는 다른 명령이 끼어드는 것을 막을 뿐, 중간에 에러가 나도 앞에서 한 쓰기를 되돌리지 않는다. DB 트랜잭션의 "전부 아니면 전무"와 다르다. 진입 스크립트라면 `INCR` 뒤 `ZADD` 전에 실패할 때 번호 하나가 비고(입장 한 자리 낭비), 사용자는 재시도에서 새 번호를 받는다. 그래서 쓰기 순서를 "중간에 끊겨도 덜 나쁜 쪽"으로 정하는 것이 중요하다([Redis에서 두 곳을 지울 때 순서가 왜 중요한가](2026-10-29-redis-write-order-recovery.md)).

## 이 프로젝트에서는

대기열은 예매 서비스(`reservation-service`)에 있고, 대기열 담당이 구현했다. 설계 근거는 [ADR 0015](../../../decisions/0015-queue-admission-guards.md)와 [ADR 0031](../../../decisions/0031-queue-token-api-and-ready-status.md)이다.

### 같은 요청의 기준은 (회차, 사용자)
진입 API는 `POST /api/v1/schedules/{scheduleId}/queue`이고 `Idempotency-Key` 헤더를 받지 않는다. 회차 ID는 Redis 키(`queue:{scheduleId}:waiting`)로, 사용자 ID는 ZSET 멤버로 들어가므로 "이 회차 줄에 이 사람이 있나"가 곧 멱등 확인이다.

결제·좌석 선점이 `Idempotency-Key`를 쓰는 것과 다르다.

| | 대기열 진입 | 좌석 선점·결제 |
|---|---|---|
| 같은 요청의 기준 | (회차, 사용자). 한 사람은 한 회차 줄에 한 번만 선다 | 요청마다 클라이언트가 만든 `Idempotency-Key` |
| 왜 | 새로고침·다른 탭·다른 기기에서 키가 새로 만들어져도 같은 사람이면 중복을 막아야 한다 | 한 사람이 서로 다른 선점·결제를 여러 번 할 수 있어 사용자 ID만으로는 같은 요청인지 모른다 |
| 다시 보냈을 때 응답 | 지금 상태(현재 순번)를 다시 계산해 준다 | 처음 처리 결과를 저장해 두고 그대로 돌려준다 |
| 근거 | ADR 0015·0031 | [ADR 0032](../../../decisions/0032-seat-hold-result-and-idempotency-contract.md), [ADR 0017](../../../decisions/0017-payment-idempotency-in-progress.md) |

이렇게 업무상 원래 하나뿐인 값으로 중복을 막는 방식을 "자연 키(natural key) 멱등"이라고 부른다.

### 진입 Lua 흐름
`reservation-service/src/main/resources/redis/queue/enter.lua`

```lua
-- 1) 판매 시간 확인 (쓰기 없음)
if now < 오픈 시각 then return {'SALE_NOT_OPEN'} end
if now >= 판매 종료 시각 then return {'SALE_CLOSED'} end

-- 2) 이미 입장 토큰을 받은 사람인가 (issued ZSET, 점수 = 발급 시각)
issuedAt = ZSCORE issued userId
if issuedAt 있음 then
    if 아직 유효(T=10분 안) then
        ZREM waiting userId          -- 남아 있던 대기 자리 정리
        return {'TOKEN_ISSUED'}      -- 새 번호 없음
    end
    ZREM issued userId; ZREM waiting userId   -- 만료됐으면 기록을 지우고 아래로
end

-- 3) 이미 줄에 서 있나  <- 멱등의 핵심
seq = ZSCORE waiting userId
if seq 있음 then return {'ALREADY_IN_QUEUE', seq, admitted} end

-- 4) 처음이면 번호 발급
seq = INCR seq
ZADD waiting seq userId
EXPIREAT seq / waiting (공연 시작 + 보관 기간 뒤 삭제)
return {'JOINED', seq, admitted}
```

Lua는 결과 종류와 숫자만 돌려주고, 상태(`WAITING`·`READY`) 판단, 순번 계산, HTTP 응답은 애플리케이션이 한다(`QueueService.enter`, `QueueController.enter`). 상태를 바꾸는 쓰기는 모두 Lua 안에서 끝나므로, Lua 실행 뒤 애플리케이션이 죽거나 응답이 유실돼도 같은 요청을 다시 하면 같은 결과가 나온다(ADR 0031 결정 5).

### 다시 보냈을 때 일어나는 일

| 상황 | Lua 결과 | 응답 | Redis 변화 |
|---|---|---|---|
| 처음 진입 | `JOINED` | 201 Created, `Location: /queue/me`, "대기열에 등록되었습니다" | `seq` +1, `waiting`에 추가 |
| 다시 진입(새로고침·연타·응답 유실 뒤 재시도) | `ALREADY_IN_QUEUE` | 200 OK, "이미 대기열에 진입했습니다" | 없음 |
| 토큰을 받았고 10분 안 | `TOKEN_ISSUED` | 200, `READY` | `waiting` 잔여만 정리 |
| 토큰을 받았고 10분 지남 | `JOINED`(새 번호) | 201, 맨 뒤 | 옛 기록 삭제 후 새로 줄 섬 |
| 오픈 전·판매 종료 뒤 | `SALE_NOT_OPEN`·`SALE_CLOSED` | 에러 `SALE_NOT_OPEN` | 없음 |

첫 요청은 201, 다시 보낸 요청은 200으로 응답이 다르고, 순번 숫자도 시간이 지나면서 줄어든다. 그래도 서버 상태(번호표가 한 번만 발급됨)는 같으므로 멱등이다.

### 일부러 멱등이 아닌 경계
토큰 유효 시간 T(10분)가 지난 뒤 다시 진입하면 새 번호로 맨 뒤에 선다. 멱등의 범위가 "한 번의 줄서기"인 셈이다. 이렇게 하지 않으면 토큰을 받은 사람이 재진입으로 토큰 유효 시간을 무한히 늘려 "최대 동시 입장 인원 = 초당 입장 인원 N × T"가 깨진다(ADR 0015 맥락 3).

### 키 이름의 중괄호
`QueueKeys`는 키를 `queue:{31}:seq`처럼 회차 ID를 중괄호로 감싸 만든다. Redis Cluster는 중괄호 안 문자열(해시 태그)로만 슬롯을 계산하므로 한 회차의 키가 모두 같은 노드에 놓인다. Cluster에서 Lua 스크립트는 같은 슬롯의 키만 다룰 수 있어서 필요한 장치다. 지금은 Redis 한 대라 당장 효과는 없지만, 나중에 Cluster로 옮겨도 스크립트를 고치지 않아도 된다.

### 검증
`reservation-service/src/test/java/com/iseonjwa/reservation/queue/infrastructure/RedisQueueRepositoryTest.java`의 `같은_사용자가_동시에_10번_진입해도_seq는_하나다`가 스레드 10개를 `CountDownLatch`로 동시에 출발시켜 같은 사용자로 진입한 뒤 다음을 확인한다.
- `seq` 카운터가 `"1"`이다(번호표가 한 번만 발급됨).
- `JOINED`가 정확히 1건이다(나머지 9건은 `ALREADY_IN_QUEUE`).
- 10건 모두 seq 1을 받았다.

2026-11-04에 테스트용 Redis(docker compose `iseonjwa-test-redis-1`)로 `./gradlew :reservation-service:test --tests '*RedisQueueRepositoryTest*'`를 실행해 진입 13건을 포함한 40건이 모두 통과했다.

## 면접 포인트
- **Q. 대기열 진입 API를 어떻게 멱등하게 만들었나?**
  (회차, 사용자)를 자연 키로 써서 "이 회차 줄에 이 사용자가 이미 있으면 기존 번호를 돌려준다"는 확인을 번호 발급과 같은 Redis Lua 스크립트에 넣었다. 같은 사용자의 동시 요청 10개를 보내도 번호표가 한 번만 발급되는 것을 테스트로 확인했다.
- **Q. Lua 스크립트를 쓰면 멱등해지나?**
  아니다. Lua는 원자성만 준다. 멱등은 "이미 처리했으면 다시 쓰지 않는다"는 로직이 만들고, Lua는 그 확인과 쓰기 사이에 다른 요청이 끼어들지 못하게 한다. 또 Lua는 중간에 실패해도 앞의 쓰기를 롤백하지 않으므로 쓰기 순서를 따져야 한다.
- **Q. 결제처럼 Idempotency-Key를 쓰지 않은 이유는?**
  대기열은 한 사람이 한 회차 줄에 한 번만 서는 것이 업무 규칙이라 사용자 ID가 자연 키다. 새로고침이나 다른 기기에서는 클라이언트가 새 키를 만들기 때문에, 키 기반이면 오히려 같은 사람의 중복을 놓친다. 결제는 한 사람이 여러 번 결제할 수 있어 요청별 키가 필요하다.

## 참고
- RFC 9110 9.2.2 Idempotent Methods: https://www.rfc-editor.org/rfc/rfc9110#section-9.2.2
- Redis 문서, Scripting with Lua(스크립트 실행 중 다른 명령 차단, 에러 시 롤백 없음): https://redis.io/docs/latest/develop/programmability/eval-intro/
- Redis 문서, Cluster 해시 태그: https://redis.io/docs/latest/operate/oss_and_stack/reference/cluster-spec/#hash-tags
- 이 저장소: `enter.lua`, `RedisQueueRepository`, `QueueService.enter`, `QueueController.enter`, ADR 0015·0031
