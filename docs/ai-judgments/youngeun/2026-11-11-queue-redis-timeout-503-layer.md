---
title: 대기열 Redis 시한 초과를 503으로 바꾸는 위치로, 컨트롤러 예외 처리기(B) 대신 저장소에서 대기열 예외로 바꾸는 안(A)을 골랐다
date: 2026-11-11
updated: 2026-11-11
judge: youngeun
ai: Claude (Cowork, 코드 읽기와 설계 비교)
verdict: 수용
area: 계층형 아키텍처
presentation: 후보
shared: 예
---

# 대기열 Redis 시한 초과를 503으로 바꾸는 위치로, 컨트롤러 예외 처리기(B) 대신 저장소에서 대기열 예외로 바꾸는 안(A)을 골랐다

## 맥락
- 해결하려던 작업: ISE-59 중 "서킷이 닫혀 있을 때 나는 개별 타임아웃(`QueryTimeoutException`)도 500이 아니라 503으로 응답". 지금은 대기열에 처리기가 없어 공통 처리기의 마지막 `Exception` 처리로 500 `INTERNAL_SERVER_ERROR`(Retry-After 없음)가 나간다. 프론트 대기열 화면은 500을 재시도하지 않고 실패로 끝내므로, Redis가 잠깐 느려도 사용자가 대기 흐름에서 튕겨 나간다.
- AI 활용 방식: 대기열의 Redis 호출 위치(`RedisQueueRepository` 네 메서드), 공통 예외 처리기, 프론트 재시도 규칙(`queueAdmission.ts`), 입장 스케줄러 코드를 읽게 하고, 503으로 바꾸는 위치를 비교하게 했다.

## AI 제안
- A: `RedisQueueRepository`에서 `QueryTimeoutException`을 잡아 domain의 대기열 예외로 바꾸고, 대기열 컨트롤러 전용 처리기가 503 + `Retry-After: 3`으로 응답한다. Redis 연결 실패(`RedisConnectionFailureException`)도 같은 예외로 묶자고 추천했다.
- B: 대기열 컨트롤러 전용 처리기에서 `QueryTimeoutException`을 바로 503으로 바꾼다.
- AI 추천은 A. 이유:
  - `QueryTimeoutException`은 DB 조회 시한 초과에서도 나므로, 저장소에서 바꾸면 대기열 Redis에서 난 것만 503이 된다.
  - Redis 같은 기술 예외가 infrastructure 밖으로 새지 않아 4계층 원칙에 맞는다.
  - 저장소 단위 테스트로 변환을 확인할 수 있다.
  - 입장 스케줄러는 컨트롤러 처리기를 거치지 않는다. A면 지나가는 Redis 지연을 WARN 한 줄로, 진짜 버그를 ERROR로 나눌 수 있다. 다만 스케줄러는 이미 회차별 try-catch로 다음 회차와 다음 주기를 이어 가므로 이 이득은 로그를 정리하는 정도라고 AI가 정정했다.
- B의 장점으로는 처리기 하나로 끝나 빠르다는 점을 냈다.

## 판단
- 결과: 수용(A)
- 판단자: youngeun
- 근거: 미기재. A·B 장단점과 스케줄러 쪽 차이 설명을 들은 뒤 "그럼 A로 할 테니 결정기록 남겨줘."
- Redis 연결 실패도 같은 예외로 묶자는 추천도 받아들였다: "Redis 연결 실패도 묶을게."
- 검토한 대안: B(컨트롤러 처리기만), 공통 처리기에서 바꾸기(다른 서비스까지 바뀌어 이전에 제외)

## 반영
브랜치 `feat/ISE-59-queue-redis-fallback`.
- domain: `QueueStorageUnavailableException`(원인 예외만 담는다. 재시도 시간은 담지 않는다)
- infrastructure: `RedisQueueRepository`의 `call()`이 네 메서드의 Redis 호출을 감싸 `QueryTimeoutException`·`RedisConnectionFailureException`을 위 예외로 바꾼다
- application: `QueueErrorCode.QUEUE_TEMPORARILY_UNAVAILABLE`(503), `QueueAdmissionService`는 이 예외면 WARN 한 줄(스택 트레이스 없이) 뒤 다음 회차를 계속 처리한다
- presentation: `QueueExceptionHandler`(`assignableTypes = QueueController.class`, `@Order(HIGHEST_PRECEDENCE)`)가 503 + `Retry-After` + 실패 응답으로 답한다. `Retry-After`는 `QueueProperties.pollMin()`(3초)에서 읽는다
- SA MASTER 11-3 에러 코드 표에 `QUEUE_TEMPORARILY_UNAVAILABLE`을 더했다. SA는 Redis 서킷 OPEN을 `SERVICE_UNAVAILABLE`로 적었지만, Gateway 인증 확인 실패의 503과 측정·로그에서 가르려고 대기열 전용 코드를 쓴다

## 검증
- `RedisQueueRepositoryFailureTest` 4건 통과: 시한 초과·연결 실패가 대기열 예외로 바뀌고 원인이 남는다, 다른 예외는 그대로 나간다, 네 메서드 모두 변환한다
- `QueueStorageUnavailableApiTest` 4건 통과: 진입·순번 조회·토큰 API가 503, `Retry-After: 3`, `QUEUE_TEMPORARILY_UNAVAILABLE`. 다른 예외는 500이고 `Retry-After`가 없다(처리기 순서도 함께 확인)
- `QueueAdmissionServiceTest` 4건 통과: 한 회차에서 이 예외가 나도 다음 회차를 처리한다
- 같은 실행에서 `LotteryPaymentMetricsTest` 1건이 실패했다(추첨 5분 경계 299.9초). 대기열과 무관하고 다른 PC에서는 통과해, 로컬 Docker와 PC 시계 차이로 본다
- 부하 측정: k6는 500과 503을 같은 방식(Q2 3초 뒤 재시도, Q1 재시도 없음)으로 다루므로 부하량은 같고 결과의 오류 코드만 바뀐다. 11/9~10 배포 측정에서는 대기열 Redis 시한 초과가 없었다.

## 판단 이력
- 2026-11-11 수용: A로 결정
- 2026-11-11 수용: Redis 연결 실패도 같은 예외로 묶음
- 2026-11-11 수정: AI가 처음 제안한 infrastructure 상수 `RETRY_AFTER_SECONDS = 3`을 사용자가 "이거를 infrastructure가 정해도 돼?"라고 지적했다. AI가 재시도 시간은 응답 정책이라 infrastructure에 맞지 않는다고 인정해, 예외에서 재시도 시간을 빼고 처리기가 `pollMin()`에서 읽게 바꿨다

## 발표·면접 포인트
- 같은 503 변환이라도 기술 예외를 infrastructure에서 도메인 예외로 바꾸면, DB와 Redis의 같은 이름 예외가 섞이지 않고 계층 경계가 지켜진다.
- 바꾸기 전에 프론트 재시도 규칙과 부하 테스트 스크립트가 500·503을 어떻게 다루는지 확인해, 사용자 경험은 바뀌고 측정 결과의 부하량은 그대로임을 미리 확인했다.
