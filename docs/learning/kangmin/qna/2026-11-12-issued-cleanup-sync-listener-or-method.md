---
title: 선점 뒤 대기열 issued 정리는 동기·비동기, 리스너·메서드 중 무엇으로 하나
date: 2026-11-12
updated: 2026-11-13
asker: kangmin
level: 중급
tags: [queue, seathold, transaction, spring-event, redis, ISE-207]
---

# 선점 뒤 대기열 issued 정리는 동기·비동기, 리스너·메서드 중 무엇으로 하나

## 질문
ISE-207에서 입장 토큰을 선점 성공 때 소모하면 대기열 `issued`에서 그 사용자를 지워야 한다(`ZREM`). 동기로 할지 비동기로 할지, 동기라면 이벤트 리스너로 할지 메서드 호출로 할지.

## 확인한 사실(origin/dev `30ad99ab`, 코드 읽기)
- 대기열(`queue`)과 선점(`seathold`)은 둘 다 reservation-service 안의 모듈이다. 같은 프로세스라 Spring 이벤트도, 직접 호출도 된다.
- `SyncSeatHoldService.hold`는 `@Transactional`이 아니라 `writeTransaction.execute(...)`로 트랜잭션을 직접 연다. `execute`가 돌아오면 커밋은 끝나 있다.
- 거절 검사(같은 `jti`)는 `holdInTransaction` 안에서 예외를 던진다. 그 트랜잭션은 롤백된다.
- reservation-service에는 아직 `ApplicationEventPublisher`·`@TransactionalEventListener`·`@Async`를 쓰는 곳이 없다.
- 모듈 사이 호출은 "인터페이스는 부르는 쪽 응용 계층, 구현은 다른 모듈" 방식이 이미 있다(`LotteryHoldExpiryHook`, ADR 0034).
- Spring 7.0.10 소스에서 `AFTER_COMMIT` 리스너는 `afterCompletion`에서 실행되고, 거기서 난 예외는 `TransactionSynchronizationUtils.invokeAfterCompletion`이 잡아 로그만 남긴다. 리스너 예외가 선점 응답을 500으로 만들지 않는다(소스 확인, 실행 확인 안 함).

## 1. 동기 vs 비동기
| | 동기(요청 스레드에서 커밋 뒤) | 비동기(`@Async`·Kafka) |
|---|---|---|
| 응답 시점의 상태 | 응답 전에 지워져 있다. 바로 대기열로 가도 새 번호를 받는다 | 응답과 정리 사이에 틈. 그 사이 진입하면 옛 토큰을 다시 받고, 선점 403 뒤 재정리로 회복한다 |
| 비용 | Redis 명령 1회만큼 응답이 늦다. Redis가 느리면 시한까지 늦다 | 응답은 안 늦다. 실행기 설정·종료 때 유실·재시도를 새로 다뤄야 한다 |
| 지금 코드와의 거리 | 바로 붙는다 | `@EnableAsync`부터 새로 들인다 |

고른 것: 동기. 비용이 명령 1회이고, 차단기(`queue-redis`, #574)와 짧은 시한으로 Redis 장애 때 늦어지는 상한을 둘 수 있다. 감수: Redis가 느리면 그만큼 선점 응답이 늦다. 바꿀 조건: 부하 측정에서 선점 p95에 이 구간이 보이면 비동기로 옮긴다.

## 2. 동기라면 리스너 vs 메서드
둘 다 요구사항을 모두 처리할 수 있다. 처음에 "거절 경로는 리스너로 못 한다"고 썼던 것은 과장이었다. 거절 경로는 `AFTER_ROLLBACK` 리스너를 하나 더 두면 된다(11/13 정정).

| | 리스너(`AFTER_COMMIT` + `AFTER_ROLLBACK`) | 훅 인터페이스 메서드 호출 |
|---|---|---|
| 필요한 부품 | 이벤트 2종(소모·재사용 거절), 리스너 2개, `ApplicationEventPublisher` 주입 | 인터페이스 1개, queue 구현 1개, `hold`에서 호출 2곳 |
| 새 선점만 정리 | 새 선점 분기(`markHeld` 뒤)에서만 발행하면 자연히 된다 | `SyncHoldOutcome.Held`가 새 선점과 재요청에 같이 쓰여 구분 표시가 필요하다 |
| DB 커넥션 | 리스너는 `triggerAfterCompletion`에서 돌고, 커넥션은 그 뒤 `cleanupAfterCompletion`에서 반납된다. `ZREM` 동안 커넥션을 쥔다 | `execute` 반환 뒤 호출이라 커넥션을 먼저 돌려준다 |
| 예외 | Spring이 잡고 ERROR 로그만 남긴다 | 구현이 잡아야 한다(안 잡으면 커밋됐는데 500) |
| 선례·테스트 | 저장소에 선례 없음. 리스너는 단위 테스트, 발행은 `@RecordApplicationEvents`로 보지만 "커밋 뒤에만 실행"은 트랜잭션을 여는 테스트에서만 확인된다 | `LotteryHoldExpiryHook` 선례, 가짜 구현으로 단위 테스트 |
| 빠질 위험 | 트랜잭션 밖에서 발행하면 기본값(`fallbackExecution=false`)에서 오류 없이 실행되지 않는다 | 호출 위치를 빠뜨리면 실행되지 않는다(성공·거절, 접수형이면 소비자까지) |
| 흐름 읽기 | `SyncSeatHoldService`만 보면 정리가 안 보인다 | `hold` 안에 드러난다 |

커넥션 차이가 커지는 조건: reservation-service 풀은 5개, 대기 시한 1초, Redis 명령 시한 200ms다(config-repo). Redis가 느린 동안 리스너 방식은 선점마다 커넥션을 최대 200ms 더 쥔다(설정값으로 계산, 측정 안 함). 평소에는 명령 1회라 차이가 작을 것으로 본다(측정 안 함).

정리: 기능 차이는 없다. 메서드는 커넥션을 먼저 반납하고 선례가 있다. 리스너는 새 선점만 고르기 쉽고 결합이 느슨하다. 어느 쪽을 고를지는 담당 합의로 정한다.

## 보충(11/13): 데이터 흐름으로 본 두 차이

### 새 선점만 고르기
`holdInTransaction`은 같은 멱등키 재요청(`replay`)과 새 선점 두 갈래가 모두 `SyncHoldOutcome.Held`를 돌려준다. 리스너는 발행 줄을 새 선점 갈래(`markHeld` 뒤)에 두면 재요청에서 저절로 빠진다. 메서드는 커넥션을 먼저 반납하려고 `execute` 밖에서 부르므로, 결과만으로는 갈래를 알 수 없어 구분 표시가 필요하다. 다만 재요청의 `ZREM`은 보통 이미 지운 항목을 지우는 일이라 해가 작다(추론). 작은 이점으로 본다.

### 커밋 뒤 예외의 행방
```
리스너: execute → DB COMMIT → invokeAfterCompletion { try { ZREM 💥 } catch { log.error } } → 커넥션 반납 → execute 정상 반환 → 201
메서드: execute → DB COMMIT → 커넥션 반납 → execute 정상 반환 → release() ZREM 💥 → 잡지 않으면 500(좌석은 확정), 재시도는 replay로 201
```
커밋은 이미 끝나 되돌릴 것이 없고, 갈리는 것은 응답 코드다. 메서드는 구현에서 직접 잡아야 리스너와 같아진다. 리스너는 실패가 로그 한 줄로만 남아, 지표가 없으면 알아채기 어렵다.

## 확인할 질문
- 11/9 대기열 담당 의견과 ADR 0053 초안은 "동기 리스너"다. 메서드로 바꾸면 대기열·선점 담당 합의와 ADR 문장 수정이 필요하다.
- 접수형 선점(`AsyncSeatHoldService`)도 입장 토큰을 소모하는가. 그렇다면 실제 선점이 끝나는 소비자 쪽에서도 같은 호출이 필요하다.
- `ZREM`에 대기열 차단기와 명령 시한을 같이 걸 것인가(시한 값은 확인 못 함).
