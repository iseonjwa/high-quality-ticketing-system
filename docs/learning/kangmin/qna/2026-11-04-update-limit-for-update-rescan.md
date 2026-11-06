---
title: UPDATE ... IN (SELECT ... LIMIT n FOR UPDATE)가 n개보다 많이 바꿀 때 고치는 세 가지 방법은 어떻게 다른가
date: 2026-11-04
updated: 2026-11-06
asker: GGangMY
level: 고급
tags: [postgresql, sql, query-plan, locking, flaky-test]
---

# UPDATE ... IN (SELECT ... LIMIT n FOR UPDATE)가 n개보다 많이 바꿀 때 고치는 세 가지 방법은 어떻게 다른가

## 질문
추첨석 전환 쿼리가 실행 계획에 따라 LIMIT보다 많은 좌석을 바꾼다. 고치는 선택지인 `= ANY(ARRAY(...))`, `WITH ... AS MATERIALIZED`, SELECT와 UPDATE 분리를 각각 자세히 설명해 달라.

## 짧은 답
세 방법의 공통 원리는 "고르는 일을 한 번만 하게 만든다"이다. 실험 조건에서 셋 다 LIMIT을 지켰고, 다른 트랜잭션이 좌석을 선점 중일 때도 그 좌석을 빼고 다음 좌석을 골랐다. 차이는 그 보장을 무엇으로 얻느냐와 코드에 미치는 영향이다. 저장소에 같은 형태가 이미 있고 쿼리 한 문장만 바뀌는 `= ANY(ARRAY(...))`를 권한다. 어느 방법을 고르든 호출부의 `converted < count` 검사는 `!=`로 바꾸는 편이 낫다.

## 자세히

### 문제의 원리
`IN (SELECT ... LIMIT n FOR UPDATE)`는 플래너가 조인으로 바꿀 수 있다. 통계가 테이블이 거의 비었다고 믿으면 서브쿼리를 Nested Loop의 안쪽에 두고 Materialize 없이 바깥 행마다 다시 실행한다. 다시 실행할 때 이 문장이 이미 바꾼 행은 잠금 단계(LockRows)에서 빠지고, LIMIT이 다음 행을 고른다. 그래서 조건에 맞는 행이 전부 바뀔 수 있다.

Hash Semi Join이나 Materialize 계획이 나오면 서브쿼리 결과가 고정되어 정상으로 동작한다. 정상 동작이 계획 선택에 달려 있다는 것이 문제다.

좌석 1·2·3이 물리적으로 이 순서로 있고 `LIMIT 1`일 때의 흐름은 다음과 같다. 최종 결과(3행 전환)는 실행으로 확인했고, 단계별 내부 동작은 PostgreSQL 동작 원리에 따른 설명이다.

| 바깥 행 | 서브쿼리 재실행 | 고른 행 | 결과 |
|---|---|---|---|
| 1 | 1을 잠금 | 1 | 일치, 1 전환 |
| 2 | 1은 이 문장이 이미 바꿔서 건너뜀 | 2 | 일치, 2 전환 |
| 3 | 1·2를 건너뜀 | 3 | 일치, 3 전환 |

문장 시작 시점의 스냅샷으로 읽으므로 서브쿼리는 바뀌기 전의 1번을 여전히 후보로 본다. 건너뛰는 일은 그다음 잠금 단계에서 일어나고, LIMIT은 잠금 단계를 통과한 행만 세므로 다음 좌석까지 내려간다.

### 1안: `= ANY(ARRAY(...))`
```sql
UPDATE schedule_seats SET grade = 'LOTTERY', ...
WHERE id = ANY(ARRAY(
    SELECT id FROM schedule_seats
    WHERE schedule_id = :scheduleId AND grade = :sourceGrade
      AND status = 'AVAILABLE' AND deleted_at IS NULL
    ORDER BY id
    LIMIT :seatCount
    FOR UPDATE))
```
- 동작: 바깥 행을 참조하지 않는 `ARRAY(서브쿼리)`는 InitPlan이 되어 먼저 한 번 실행되고, 결과 배열이 고정값처럼 쓰인다. 조인이 아니므로 다시 실행될 자리가 없다.
- 장점: 고치는 범위가 쿼리 문장뿐이고 반환값과 호출부가 그대로다. `OutboxRepository.claimNext`가 이미 이 형태다.
- 대가: `IN`과 결과가 같아 보여서 "정리"로 되돌리기 쉽다. 이유를 `Note:`로 남겨야 한다.

### 2안: `WITH ... AS MATERIALIZED`
```sql
WITH picked AS MATERIALIZED (
    SELECT id FROM schedule_seats
    WHERE ...
    ORDER BY id
    LIMIT :seatCount
    FOR UPDATE)
UPDATE schedule_seats s SET grade = 'LOTTERY', ...
FROM picked p WHERE s.id = p.id
```
- 동작: CTE를 먼저 계산해 저장하고 바깥 조인은 저장된 결과를 읽는다. 계획에 Nested Loop가 나와도 CTE Scan이라 안의 잠금 단계는 다시 돌지 않는다.
- 장점: "먼저 고르고, 고른 것을 바꾼다"는 순서가 글로 드러난다. `MATERIALIZED`가 있어 의도도 명시된다.
- 대가: 저장소에 없는 형태가 하나 늘고 문장이 길어진다. `UPDATE ... FROM` 문법도 함께 알아야 한다.

### 3안: SELECT와 UPDATE 분리
```java
List<Long> ids = repository.findIdsForUpdate(scheduleId, grade, count); // ... LIMIT :count FOR UPDATE
if (ids.size() < count) throw new BusinessException(LOTTERY_SEATS_NOT_ENOUGH);
repository.updateGradeByIds(ids, actorId);                              // WHERE id IN (:ids)
```
- 동작: 고르기와 바꾸기가 서로 다른 문장이라 첫 문장이 다시 실행될 수 없다. 잠금은 트랜잭션이 끝날 때까지 유지되므로 그 사이에 다른 선점이 끼지 못한다.
- 장점: SQL 특성을 몰라도 읽힌다. 모자라면 UPDATE 전에 끝낼 수 있고, 고른 ID를 로그에 남기기도 쉽다.
- 대가: DB 왕복이 두 번이고 메서드가 늘어난다. 저장소 포트의 "한 번에 전환" 계약도 바뀌거나 구현 안에 두 호출이 숨는다. 트랜잭션 밖에서 호출하면 FOR UPDATE 잠금이 바로 풀리므로 트랜잭션이 필수다. 빈 목록을 `IN`에 넘기지 않게 막아야 한다.

### 비교
| | 1안 ANY(ARRAY) | 2안 CTE | 3안 두 문장 |
|---|---|---|---|
| 한 번만 고르는 근거 | InitPlan | CTE 결과 저장 | 별도 문장 |
| 왕복 | 1 | 1 | 2 |
| 바뀌는 범위 | 쿼리 | 쿼리 | 쿼리, 저장소 메서드 |
| 저장소 선례 | 있음(`OutboxRepository`) | 없음 | 없음 |
| 되돌리기 위험 | `IN`으로 정리될 수 있음 | 낮음 | 낮음 |

### 세 방법과 별개로 고칠 곳
`LotteryAdminService.convert`는 `converted < count`만 막는다. 그래서 3석이 바뀌어도 요청이 201로 성공했다. `revert`처럼 `!=`로 검사하면 쿼리가 또 어긋나도 롤백되어 잘못된 데이터가 남지 않는다. 쿼리 수정이 원인 제거이고 이 검사는 재발 시 피해를 막는 방어선이다.

## 이 프로젝트에서는
- 대상: `SpringDataScheduleSeatRepository`의 `updateGradeToLottery`, `updateGradeFromLottery`.
- 이미 안전한 선례: `OutboxRepository.claimNext`의 `= ANY(ARRAY(...))`.
- 호출부: `LotteryAdminService.convert`, `revert`. 두 경로 모두 `@Transactional` 안에서 호출된다.

## 면접 포인트
- `LIMIT`이 있는 서브쿼리를 썼는데 왜 더 많은 행이 바뀔 수 있나: 실행 계획에 따라 서브쿼리가 여러 번 실행될 수 있고, 잠금 단계가 자기 문장이 바꾼 행을 건너뛰기 때문이다.
- 전체 테스트에서만 가끔 실패한 이유는 무엇인가: 테스트 중 데이터가 늘고 줄며 autoanalyze 시점에 따라 통계가 바뀌고, 그 통계가 실행 계획을 바꾸기 때문으로 본다.
- 왜 원인 쿼리와 호출부 검사를 둘 다 고치나: 앞은 원인 제거이고 뒤는 다른 이유로 어긋나도 잘못된 값을 커밋하지 않게 하는 방어선이다.

## 참고
실행 결과(PostgreSQL 17 일회용 컨테이너, 3석, `LIMIT 1`, 통계가 1행이라고 믿게 만든 상태):

| 쿼리 | 경합 없음 | 1번 좌석을 다른 트랜잭션이 선점 중 |
|---|---|---|
| 원래 `IN` | 1·2·3번 전환 | 1번은 빠지고 2·3번 전환 |
| 1안 | 1번 전환 | 2번 전환 |
| 2안 | 1번 전환 | 2번 전환 |
| 3안 | 1번 전환 | 2번 전환 |

- 실험은 psql에서 단순화한 테이블로 했다.
- 실제 실패한 실행에서 이 계획을 탔는지는 직접 확인하지 못했다. 원리 재현과 증상 일치까지가 근거다.

## 11/6 수정 결과
1안으로 고쳤다. `LotteryGradeUpdateLimitTest`가 실제 `schedule_seats`와 Spring Data 네이티브 쿼리로 같은 조건을 만든다. 통계는 테스트 트랜잭션 안에서 `pg_class`를 고쳐 만들고 롤백한다.

| 변이 | 결과 |
|---|---|
| 전환 쿼리를 `IN`으로 되돌림 | 전환 회귀 테스트만 실패(`expected 1, was 3`) |
| 되돌리기 쿼리를 `IN`으로 되돌림 | 되돌리기 회귀 테스트만 실패(`expected 1, was 3`) |
| `converted != count` 검사 제거 | `LotteryAdminServiceTest`만 실패 |

- 되돌리기는 `ORDER BY id DESC`라 조건이 하나 더 필요했다. 물리 순서가 ID 오름차순이면 바깥 스캔이 마지막 행에서야 일치하므로 다시 실행돼도 넘칠 틈이 없다고 보았고, 테스트는 행 버전을 ID 내림차순으로 놓아 3행 전환을 재현했다. 오름차순 배치에서 1행만 바뀌는지는 실행하지 않았다.
- 원래 쿼리를 그대로 돌리는 대조군을 두었다. 재현 조건이 깨지면 회귀 테스트가 그냥 통과하는 대신 대조군이 실패한다.
- `RetentionCleanup`과 `IdempotencyRecordCleanup`의 `DELETE ... IN (SELECT ... LIMIT ?)`는 고치지 않았다. `FOR UPDATE`가 없어 잠금 단계에서 행을 건너뛰지 않으므로 같은 현상이 나지 않는다고 추론했고, 실행으로 확인하지는 않았다.
- 경합 상황(다른 트랜잭션이 좌석을 선점 중일 때)의 결과는 위 psql 실험까지만 확인했다. 전체 테스트는 수정 뒤 2회 통과했지만, 가끔 실패하던 증상이 사라졌다고 말하기에는 횟수가 부족하다.
