---
title: 입장 토큰 소모 뒤 issued 정리를 이벤트 리스너로 유지하자는 AI 추천을 거부하고, 선점 쪽이 트랜잭션 밖에서 대기열 메서드를 jti와 함께 부르는 방식으로 바꿨다
date: 2026-11-13
updated: 2026-11-13
judge: youngeun
ai: Claude (Cowork, 설계 비교)
verdict: 거부
area: 대기열
presentation: 후보
shared: 예
---

# 입장 토큰 소모 뒤 issued 정리를 이벤트 리스너로 유지하자는 AI 추천을 거부하고, 선점 쪽이 트랜잭션 밖에서 대기열 메서드를 jti와 함께 부르는 방식으로 바꿨다

## 맥락
- 해결하려던 작업: ISE-207(ADR 0053) 중 대기열 몫. 선점이 성공하면 대기열 `issued`에서 그 사용자의 토큰 기록을 지워야 바로 다시 줄을 설 수 있다(정할 것 2·4).
- 11/9 내 댓글로 "선점은 `SeatHeldEvent`만 발행하고 대기열이 `@TransactionalEventListener(AFTER_COMMIT)`로 받는다"를 제안했고, 11/11 강민님이 동의했다. ADR 0053 결정 3에도 이 방식이 적혔다.
- 질문: "메소드 부르는 방식으로 바꿔도 되거든. 이벤트 리스너랑 둘중에 어느 쪽이 더 좋을까?"
- AI 활용 방식: ISE-207 댓글, ADR 0053·0034·0043, 대기열 코드(`JwtEntryTokenIssuer`, `RedisQueueRepository`)를 읽게 하고 두 방식을 비교하게 했다.

## AI 제안
- 두 방식 모두 기본 설정이면 동기 + 블로킹이다. 같은 선점 요청 스레드에서 ZREM이 끝난 뒤 응답하고, 실패는 선점 쪽 흐름으로 올라온다. 차이는 실행이 아니라 코드 구조다.
- 이벤트 리스너: 커밋 뒤 실행을 Spring이 맞춰 주고, 선점 쪽은 대기열 API·예외를 몰라도 된다. 대신 흐름이 선점 코드에 보이지 않고, 롤백되는 거절 경로에서는 리스너가 돌지 않아 트랜잭션 밖 발행과 `fallbackExecution`이 필요하다.
- 메서드 호출: 흐름이 보이고 거절 경로에서 부르기 쉽고 테스트가 쉽다. 대신 커밋 뒤 호출을 선점 쪽이 챙기고, 선점 쪽이 `QueueStorageUnavailableException`을 잡아야 한다.
- AI 추천은 이벤트 유지. 이유: 이미 합의돼 ADR에 적혔고, 마감(11/16) 앞에서 바꾸면 세 담당에게 다시 확인받아야 한다.
- 함께 낸 제안: 거절 경로의 재정리가 다른 탭·늦은 재시도의 옛 토큰으로 불릴 때 새로 받은 토큰 기록을 지우지 않도록, `jti`를 넘겨 `issued`의 발급 시각으로 다시 계산한 `jti`와 같을 때만 지운다. `jti`는 `entry:{scheduleId}:{userId}:{발급 시각 ms}`의 SHA-256 앞 16바이트라 거꾸로 풀 수 없어, 회차·사용자·`jti`를 함께 받는다.

## 판단
- 결과: 거부 (방식은 메서드 호출로 바꿈. `jti` 비교 제안은 수용)
- 판단자: youngeun
- 근거: "동기 이벤트 리스너 방식이 커넥션을 오래 잡고 있기 때문". Spring은 커밋 → `afterCommit` 콜백 → 커넥션 반납 순서라, 동기 `AFTER_COMMIT` 리스너는 DB 커넥션을 쥔 채 Redis 정리(평소 수 ms, Redis가 느리면 시한 200ms)를 기다린다. 커넥션 대기 시한을 1초로 줄인 피크(ISE-196)에서 풀을 압박한다.
- AI가 덧붙인 조건: 메서드 방식도 `TransactionSynchronization.afterCommit` 안에서 부르면 같은 문제가 남는다. 그래서 `@Transactional` 선점 메서드가 끝난 뒤 트랜잭션 밖에서 부르기로 했다(거절 경로도 롤백 뒤 트랜잭션 밖).
- 선점 담당 동의: 최권우(좌석 선점 담당)에게 받았다.
- 메서드 이름: `discardEntryToken(scheduleId, userId, entryTokenJti)`. 리스너용으로 골랐던 `onEntryTokenConsumed`는 직접 부르는 메서드에서 누가 무엇을 하는지 덜 드러나 바꿨다.
- 검토한 대안: 이벤트 리스너 유지(AI 추천), 메서드를 `afterCommit`에서 부르기, `jti` 비교 없이 사용자 기준으로 지우기, 거절 경로 재정리 빼기

## 반영
- ISE-207 댓글(2026-11-13)로 방식 변경과 이유, 메서드 시그니처, 호출 약속(트랜잭션 밖, 거절 때 한 번 더, 예외는 잡아 로그, 추첨 선점은 부르지 않음, seathold → `queue.application` 한 방향)을 알렸다.
- ADR 0053 결정 3 문구 변경을 강민님께 요청했다.
- 구현: 브랜치 `feat/ISE-207-entry-token-discard`
  - domain: `EntryTokenJti.of(scheduleId, userId, issuedAtEpochMillis)`로 `jti` 공식을 꺼냈다. 발급기(`JwtEntryTokenIssuer`)와 정리가 같은 공식을 쓴다
  - domain `QueueRepository`: `issuedAt(scheduleId, userId)`(`Optional<Long>`), `discardToken(scheduleId, userId, issuedAtEpochMillis)`
  - infrastructure: `issuedAt`은 `ZSCORE`, `discardToken`은 `discard-token.lua`(발급 시각이 같을 때만 `ZREM`, 반환값 없음). 둘 다 `call()`로 감싸 대기열 Redis 차단기와 예외 변환을 거친다
  - application: `QueueService.discardEntryToken(scheduleId, userId, entryTokenJti)`. 밖(선점)에서 부르는 메서드라 이름에 `Entry`를 붙이고, 대기열 안쪽(저장소·Lua)은 기존 `issueToken`에 맞춰 `discardToken`으로 했다
- 문서: SA 7-3 문장, `docs/design/queue/contracts.md`(claim 표의 `jti`, "소모" 소절, 프론트 규칙, 바뀐 점)

## 검증
- `EntryTokenJtiTest`: 계약 공식대로의 값, 소문자 hex 32자, 회차·사용자·시각 중 하나만 달라도 다름
- `JwtEntryTokenIssuerTest`: 발급한 토큰의 `jti`가 `EntryTokenJti.of(...)`와 같다(꺼내기 전후 결과가 같음)
- `QueueServiceDiscardTest`: 같은 토큰이면 그 발급 시각으로 지움, 그 사이 새 토큰을 받았으면 지우지 않음, 기록이 없거나 `jti`가 없으면 아무것도 안 함, Redis 예외는 그대로 나감
- `RedisQueueRepositoryTest`(실제 Redis): 발급 시각을 ms 그대로 읽음, 같으면 지우고 다르면 남김, 기록이 없어도 오류 없음(Lua `nil` 반환), 같은 시각의 다른 사용자·다른 회차는 건드리지 않음
- `RedisQueueRepositoryFailureTest`·`RedisQueueRepositoryCircuitBreakerTest`: 두 메서드도 예외 변환과 차단기를 거침
- `QueueApiTest`: 정리 뒤 다시 진입하면 201과 새 번호, 새 토큰의 `jti`가 다름(ISE-207 완료 기준). 다른 `jti`로는 정리되지 않아 같은 토큰을 다시 받음
- 테스트 중 실수 하나: `ZSetOperations.score`는 단건(`Object`)과 여러 건(`Object...`) 두 가지가 있다. 가짜 설정에 `any()`를 쓰면 여러 건 쪽에 걸려, 실패 테스트는 예외가 안 나고 차단기 테스트의 `never()` 확인은 아무것도 검사하지 않았다. `anyString()`으로 고쳤다
- 미확인: 선점 쪽 호출(ISE-207 선점 담당 몫). ADR 0053 결정 3 문구는 강민님 확인 대기

## 판단 이력
- 2026-11-09 제안: 이벤트 발행 + 대기열 리스너(11/9 댓글)
- 2026-11-13 거부: AI의 이벤트 유지 추천 대신 메서드 호출
- 2026-11-13 수용: 메서드는 `afterCommit`이 아니라 트랜잭션 밖에서 부르기
- 2026-11-13 수용: `jti`가 같을 때만 지우기

## 발표·면접 포인트
- 이벤트와 메서드 호출은 둘 다 동기 + 블로킹일 수 있다. 고르는 기준은 실행 모델이 아니라 언제 실행되는지(커넥션을 쥔 채인지), 누가 누구를 아는지, 흐름이 코드에 보이는지다.
- `AFTER_COMMIT`은 "커밋 뒤"지만 "커넥션 반납 뒤"는 아니다. 외부 호출은 트랜잭션 밖으로 빼야 커넥션을 오래 잡지 않는다.
- 상태를 지우는 호출은 "누구의 무엇을 지우는지"를 정확히 넘겨야 한다. 사용자 ID만 넘기면 늦게 온 요청이 새 상태를 지울 수 있다.
