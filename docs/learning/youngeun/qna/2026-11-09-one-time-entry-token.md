---
title: 한 번 구매한 사용자의 입장 토큰을 어떻게 무효화하는가
date: 2026-11-09
updated: 2026-11-09
asker: kye
level: 중급
tags: [entry-token, jwt, idempotency, redis, queue, seat-hold, spring-event]
---

# 한 번 구매한 사용자의 입장 토큰을 어떻게 무효화하는가

## 질문
한 번 입장해서 선점한 사람의 입장 토큰을 무효화하려면 어떻게 해야 하나? 일회용 토큰으로 바꾸는 게 좋은가? 일회용 토큰은 블랙리스트 말고 구현 방법이 없나?

맥락: 지금은 입장 토큰이 10분 동안 유효해서, 2매를 선점한 사람이 줄을 다시 서지 않고 같은 토큰으로 2매를 더 선점할 수 있다.

## 짧은 답
막아야 할 것은 "토큰을 두 번 쓰기"가 아니라 "한 번 입장으로 두 번 사기"다. 좌석 조회와 409 뒤 재시도 때문에 토큰은 여러 번 쓰여야 한다. 그래서 선점이 성공한 시점에 무효화한다. 블랙리스트 대신 선점 테이블에 토큰의 `jti`를 unique로 저장하면, 같은 토큰으로는 선점이 하나만 생긴다(C). 여기에 선점 성공 뒤 대기열의 `issued`에서 사용자를 지워야 다시 사려면 줄을 새로 서게 된다. 삭제는 커밋 후 동기 이벤트 리스너로 한다. 아직 팀 결정은 아니다.

## 자세히

### 완전한 일회용 토큰은 맞지 않는다
입장한 사용자는 좌석 조회 → 선점 → 409 → 좌석 다시 조회 → 선점 재시도처럼 토큰을 여러 번 쓴다. 프론트(`SeatsPage.tsx`)는 화면 진입, 409(`SEAT_ALREADY_HELD`), 선점 만료, 조회 실패 후 다시 불러오기, 새로고침 때 좌석 조회를 다시 부른다. 한 번 쓰고 무효로 만들면 첫 좌석 조회에서 끝난다.

좌석 조회 때 무효화하는 것도 맞지 않는다. 두 번째 조회부터 거절되고, GET에 상태를 바꾸는 부작용이 생긴다. 이 방식은 "두 번 사기"가 아니라 "두 번 들어가기(토큰 공유)"를 막는 것이다.

### 방법 비교
| 방법 | 어디서 | 어떻게 | 장단점 |
|---|---|---|---|
| A. 블랙리스트 | Gateway | 다 쓴 `jti`를 Redis에 넣고 요청마다 확인 | 모든 요청에 Redis 조회가 붙고 Gateway의 상태 없는 검증(SA 9-5)이 깨진다 |
| B. 허용 목록 | Gateway | 대기열 `issued`에 남아 있을 때만 통과 | A와 같다 |
| C. jti unique | 선점(reservation) | 선점 저장 때 `jti`를 함께 저장하고 unique 제약 | Gateway를 그대로 둔다. 추가 조회가 없다 |
| D. 업무 규칙 | 선점(reservation) | 회차당 사용자 최대 매수를 기존 선점·예매로 센다 | 줄을 다시 서게 하지는 못한다 |

### A와 C의 차이는 확인 위치만이 아니다
A는 확인(Gateway가 목록 조회)과 쓰기(선점 성공 뒤 목록 추가)가 다른 곳에서 따로 일어난다. 두 탭이 동시에 선점하면 둘 다 "목록에 없음"을 보고 통과한다. C는 먼저 조회하지 않고 바로 INSERT한다. DB가 INSERT 순간에 unique를 판단하므로 확인과 쓰기가 한 동작이다. 대기열 진입의 Lua, 선점 멱등키의 unique 제약과 같은 원리다.

A를 Gateway에서 `SET jti NX`로 원자적으로 만들면, 선점 결과를 모르는 채 토큰을 "썼음"으로 표시하게 된다. 선점이 409로 실패해도 토큰이 소진되고, 실패 시 되돌리려 해도 타임아웃이면 성공 여부를 모른다. C는 결과를 아는 선점 DB에서 같은 트랜잭션으로 기록하므로 "썼음"과 "실제로 샀음"이 어긋나지 않는다.

### C만으로는 줄을 다시 서지 않는다
대기열은 회차마다 `seq`, `waiting`(Sorted Set), `admitted`, `issued`(Sorted Set: 사용자 → 발급 시각 ms)를 둔다. 토큰을 받으면 `waiting`에서 빠지고 `issued`에 들어간다. `issued`가 유효한 동안 진입하면 `TOKEN_ISSUED`, 토큰 API는 같은 발급 시각으로 서명한 같은 토큰을 준다. 그래서 C만 하면 같은 `jti`를 계속 받아 선점이 막히고, 줄도 다시 설 수 없다.

선점 성공 뒤 `ZREM issued <사용자>`를 하면, 다시 진입할 때 새 번호로 맨 뒤에 선다. 차례가 오면 새 발급 시각으로 서명하므로 `jti`(회차·사용자·발급 시각의 SHA-256)도 새로 나와 선점할 수 있다. 토큰 만료 때 `enter.lua`가 하던 정리를 선점 시점으로 앞당기는 셈이다. 확인 없이 지우기만 하므로 Lua로 묶을 필요가 없다.

`issued` 삭제와 C는 같이 있어야 한다.
- `issued`만 지우면: 옛 토큰이 만료 전까지 Gateway를 통과해 줄 없이 또 산다.
- C만 하면: 줄을 다시 설 수 없다.

### 거절 코드는 403 ENTRY_TOKEN_INVALID여야 한다
프론트(`frontend/src/auth/entryTokens.ts`)는 토큰을 회차마다 `sessionStorage`에 두고, 만료 전이면 대기열을 건너뛴다. 저장한 토큰을 지우는 것은 403 `ENTRY_TOKEN_INVALID`뿐이다. C의 거절을 이 코드로 주면 프론트가 토큰을 지우고 대기열로 보내고, 새 번호를 받는다. 새 코드(예: 409)를 쓰면 프론트가 토큰을 지우지 않아 만료까지 좌석 화면과 거절을 반복한다. `issued`를 지우지 않아도 대기열이 같은 토큰을 다시 줘서 반복한다.

프론트가 선점 성공 시 토큰을 지우면 정상 사용자의 경험이 낫다. 그래도 탭 복제(`sessionStorage` 복사), 토큰을 따로 빼 둔 요청, 응답을 못 받은 선점은 프론트로 막을 수 없어 C가 안전장치로 필요하다.

### issued 삭제는 커밋 후 동기 이벤트 리스너로
| 방법 | 문제 |
|---|---|
| 선점 트랜잭션 안에서 바로 ZREM | Redis는 DB 트랜잭션에 묶이지 않는다. 롤백돼도 `issued`가 지워져 좌석 없이 줄을 다시 서야 한다 |
| 선점 서비스가 커밋 후 직접 호출 | 선점 코드가 대기열 코드를 알게 된다 |
| Spring 이벤트 + AFTER_COMMIT | 커밋이 확정된 뒤에만 지운다. 선점은 이벤트만 낸다 |
| 메시지 브로커 | 같은 reservation-service 안이라 과하다 |

```java
@TransactionalEventListener(phase = AFTER_COMMIT)
public void on(SeatHeldEvent event) {
    try {
        queueService.leave(event.scheduleId(), event.userId()); // ZREM issued
    } catch (Exception e) {
        log.warn("대기열 issued 삭제 실패 scheduleId={} userId={}", event.scheduleId(), event.userId(), e);
    }
}
```

동기 리스너를 고른 이유:
- 응답이 나갈 때 삭제가 끝나 있어, 바로 대기열로 가도 같은 토큰을 다시 받는 틈이 없다.
- 실패가 같은 요청의 로그·trace에 남는다.
- `@Async` 스레드 풀, 종료 시 남은 작업, 테스트 대기 처리가 필요 없다.

대가는 선점 응답이 ZREM 시간만큼 늦어지는 것이다. 예외를 잡지 않으면 선점은 저장됐는데 500이 나가므로 반드시 잡는다. 삭제가 실패하면 토큰 만료(최대 10분)까지 다시 줄을 못 서는데, 드물다고 보고 로그로 빈도를 본다. 측정에서 이 시간이 눈에 띄면 비동기로 바꾼다.

### 성능 측정 영향
- C: 선점 INSERT에 인덱스 하나, 성공한 선점마다 ZREM 한 번. 좌석 조회는 그대로라 작다.
- A: 토큰이 붙은 모든 요청, 특히 가장 많은 좌석 조회에 Redis 조회가 붙어 크다.
- k6에서 한 토큰으로 선점 성공을 두 번 하는 시나리오가 없는지, k6가 서명하는 `jti`가 겹치지 않는지 확인한다. 적용 전후 결과는 측정 환경 기록에 구분해 적는다.

## 이 프로젝트에서는
- Gateway `EntryTokenPolicy`는 `GET /api/v1/schedules/{scheduleId}/seats`와 `POST /api/v1/schedules/{scheduleId}/reservations`(선점)만 입장 토큰을 확인한다. reservation-service는 지금 `X-Entry-Token`을 읽지 않는다.
- 대기열 Lua: `reservation-service/src/main/resources/redis/queue/enter.lua`, `issue-token.lua`
- 입장 토큰 서명: `JwtEntryTokenIssuer`(`jti` = 회차·사용자·발급 시각의 SHA-256)
- 프론트 토큰 저장·삭제: `frontend/src/auth/entryTokens.ts`, `frontend/src/api/client.ts`
- 미결정(팀 안건)
  - C를 하려면 Gateway가 `jti`를 헤더로 넘기고, 선점 테이블에 칸과 unique를 추가해야 한다. Gateway 담당과 선점 담당(최권우)이 함께 정한다.
  - `issued`를 선점 성공 때 지울지 결제 완료 때 지울지
  - 선점이 만료·취소된 뒤 같은 토큰으로 다시 선점하게 할지(허용하면 unique 대신 살아 있는 선점 중 같은 `jti`가 있는지 검사)

## 면접 포인트
- 일회용 토큰을 블랙리스트 없이 만드는 방법: 토큰을 실제로 소비하는 쓰기(선점)에 `jti`를 unique로 함께 저장한다. 확인과 쓰기가 한 동작이라 동시 요청도 하나만 통과한다.
- 왜 Gateway가 아니라 선점에서 막나: Gateway는 선점 결과를 모른다. 결과를 아는 곳에서 같은 트랜잭션으로 기록해야 "썼음"과 "샀음"이 일치한다.
- Redis 정리를 DB 트랜잭션 안에서 하면 안 되는 이유: Redis는 롤백되지 않는다. `AFTER_COMMIT`으로 커밋 뒤에 한다.
- 서버 거절 코드와 프론트 동작을 맞춰야 하는 이유: 프론트가 토큰을 지우는 조건과 다르면 만료 때까지 거절이 반복된다.

## 참고
- `docs/design/queue/contracts.md`(입장 토큰 계약), ADR 0031(토큰 API 분리, 같은 토큰 재발급)
- SA 9-5(Gateway 상태 없는 검증), SA 7-3(jti는 추적용, 폐기 목록 없음)
- Spring `@TransactionalEventListener` 공식 문서
