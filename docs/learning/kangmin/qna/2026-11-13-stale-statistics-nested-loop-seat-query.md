---
title: 새 회차의 좌석 조회가 왜 통계 하나 때문에 DB CPU를 4~5배 썼나 (오래된 통계, 행 수 추정 1, Nested Loop)
date: 2026-11-13
updated: 2026-11-13
asker: kangmin
level: 중급
tags: [postgresql, query-planner, statistics, analyze, autovacuum, join, loadtest, seat-query]
---

# 새 회차의 좌석 조회가 왜 통계 하나 때문에 DB CPU를 4~5배 썼나

## 질문
Gateway 동시 처리 상한을 끄고 좌석 조회 1차를 다시 쟀다(11/11 대비 11/13).

| 항목 | 11/11 | 11/13 |
|---|---|---|
| 실패 없이 버틴 한계 | 초당 40건 | 초당 10건 |
| 초당 20건일 때 실패율 | 0% | 40% |
| 초당 20건일 때 DB CPU | 73% | 340% |

같은 좌석 조회 쿼리 하나가 DB 안에서 오래 걸렸다. 원인은 측정용 회차(104)가 마지막 통계 수집 뒤에 생겨서 DB가 회차 104의 좌석을 1개로 추정했고, 그래서 조인 방식을 잘못 골랐다는 것이다. 해결은 `ANALYZE` 한 번이었다. 이 흐름을 자세히 풀어 달라.

## 짧은 답
- PostgreSQL은 쿼리를 실행하기 전에 **실행 계획(query plan)**을 고른다. 고르는 기준은 **비용 추정(cost estimate)**이고, 추정의 재료는 테이블마다 저장해 둔 **통계(statistics)**다. 표에 적은 "메모"가 이 통계다.
- 회차 104의 좌석 15,000행은 통계를 마지막으로 모은 뒤에 들어갔다. 통계에는 "회차 104"라는 값이 없다. 이 경우 추정 선택도(selectivity)가 0에 가깝게 나오고, PostgreSQL은 행 수 추정을 **최소 1행으로 올려 잡는다(clamp)**. 그래서 1행 추정이 나왔다.
- 1행이라고 믿으면 **Nested Loop + 인덱스 조회**가 가장 싸 보인다. 실제로는 15,000행이라 공연장 좌석 인덱스를 15,000번 따로 찾았다. 15,000행이라고 알았다면 **Hash Join**(한쪽을 해시 표로 만들어 한 번에 맞춤)을 골랐을 것이다.
- `ANALYZE`로 통계를 새로 모으면 추정이 15,000으로 맞고 계획이 바뀐다. 다만 이것은 **한 번 고친 것**이고, "새 회차를 만들고 곧바로 큰 트래픽을 받는" 패턴은 측정 때마다, 그리고 실제 티켓 오픈 때도 다시 생긴다. 재발 방지는 따로 정해야 한다(아래 "재발 방지 선택지").

확인 못 함: 이 문서를 쓰면서 배포 DB에서 `EXPLAIN`·`pg_stats`를 직접 보지 않았다. 아래의 "1행이 나온 경로"는 PostgreSQL 동작으로 가장 그럴듯한 설명이고, 확인 질문은 마지막 절에 적었다.

---

## 1. 플래너는 무엇을 보고 계획을 고르나

SQL은 "무엇을" 달라는 언어이고 "어떻게" 가져올지는 DB가 정한다. 좌석 조회 쿼리는 대략 이런 모양이다.

```sql
SELECT ss.id, ss.section, ss.status, vs.row_no, vs.seat_no
FROM schedule_seats ss
JOIN venue_seats vs ON vs.id = ss.venue_seat_id
WHERE ss.schedule_id = :scheduleId
  AND ss.deleted_at IS NULL;
```

같은 결과를 내는 방법은 여러 가지다.

| 단계 | 선택지 |
|---|---|
| `schedule_seats`에서 회차 행 찾기 | 순차 스캔(Seq Scan), 인덱스 스캔(`idx_schedule_seats_schedule_id_section`), 비트맵 스캔 |
| 두 테이블 합치기 | Nested Loop, Hash Join, Merge Join |
| 순서 | 어느 쪽을 바깥(outer)·안쪽(inner)으로 둘지 |

플래너(planner, 계획을 고르는 부분)는 조합마다 비용을 계산해 가장 싼 것을 고른다. 비용 계산에서 가장 중요한 입력이 **"각 단계에서 몇 행이 나올까"(행 수 추정, row estimate)**다. 이 숫자가 틀리면 나머지 계산이 아무리 정확해도 틀린 계획이 나온다.

## 2. 통계(메모)에는 무엇이 적혀 있나

`ANALYZE`가 테이블에서 표본을 뽑아 두 군데에 적는다.

| 위치 | 내용 | 이번 일과의 관계 |
|---|---|---|
| `pg_class.reltuples` | 테이블 전체 행 수 추정 | 전체 행 수 |
| `pg_statistic`(사람이 읽는 뷰는 `pg_stats`) | 열마다 `null_frac`, `n_distinct`(서로 다른 값 수), `most_common_vals`/`most_common_freqs`(자주 나오는 값과 비율, MCV), `histogram_bounds` | `schedule_id = 104`가 몇 행일지 계산하는 재료 |

`WHERE ss.schedule_id = 104`의 행 수 추정은 다음 순서로 계산한다.

1. 104가 MCV 목록에 있으면 그 비율을 쓴다. 예: 비율 0.1이면 전체 행 × 0.1.
2. 없으면 "MCV 바깥에 남은 비율"을 "MCV 바깥의 서로 다른 값 수"로 나눠 쓴다.
   - 남은 비율 = 1 − (MCV 비율 합) − `null_frac`

### 왜 하필 1행이 나왔나 (가장 그럴듯한 경로)

측정 DB의 `schedule_seats`는 회차 수가 많지 않고 회차마다 좌석이 같은 수만큼 들어 있다. 이러면 통계를 모을 때 **모든 회차 값이 MCV 목록에 다 들어간다**. 그 결과:

- MCV 비율 합 ≈ 1.0 → "MCV 바깥에 남은 비율" ≈ 0
- 통계에 없는 104는 2번 경로로 계산 → 선택도 ≈ 0 → 추정 행 수 ≈ 0
- PostgreSQL은 행 수 추정을 **1 미만이면 1로 올린다**(소스의 `clamp_row_est`).

사용자가 적은 "0이면 뒤 계산이 다 0이 된다"가 이 올림의 이유다. 조인 추정은 "바깥 행 수 × 안쪽 행 수 × 선택도"처럼 곱으로 이어진다. 한 단계가 0이면 이후 모든 비용이 0이 되어 어떤 계획이든 공짜로 보이고 비교가 무너진다. 그래서 "적어도 1행은 있을 수 있다"로 둔다.

참고로 통계가 **아예 없는** 테이블이라면 PostgreSQL은 기본 추정값(등호 조건이면 대략 0.5%)을 쓴다. 정확히 1이 나왔다는 것은 "통계는 있는데 그 값만 없다"는 신호에 가깝다. 이 경로가 맞는지는 아래 확인 질문 1~2로 확인한다.

## 3. 1행 추정이 어떻게 비싼 계획이 되었나

| | A. 1행이라고 믿을 때 | B. 15,000행이라고 알 때 |
|---|---|---|
| 고르는 조인 | Nested Loop (바깥 = 회차 좌석, 안쪽 = `venue_seats` 기본 키 인덱스) | Hash Join (`venue_seats`를 해시 표로 만들고 회차 좌석을 한 번 훑으며 맞춤) 또는 Merge Join |
| 플래너가 본 비용 | 인덱스 1번 조회. 해시 표를 만드는 고정 비용보다 훨씬 싸다 | 해시 표를 만드는 비용이 들지만 15,000행에 나눠 내면 싸다 |
| 실제로 한 일 | 인덱스를 **15,000번** 내려감. 한 번마다 B-tree 루트→리프→힙 페이지를 읽고(대부분 메모리 버퍼라도 CPU를 씀), 행마다 함수 호출·잠금 확인이 반복된다 | 각 테이블을 한 번씩 읽는다 |

핵심은 **Nested Loop의 비용이 바깥 행 수에 정비례**한다는 점이다. 1행일 때는 최선이지만 15,000행이면 최악에 가깝다. 플래너는 이 계획을 고른 뒤 실행 중에 행 수가 다르다는 것을 알아도 **계획을 바꾸지 않는다**(PostgreSQL은 실행 중 재계획을 하지 않는다).

### 왜 실패율과 CPU가 그만큼 튀었나

- 쿼리 한 번이 DB CPU를 몇 배 오래 쓴다 → 초당 20건만 들어와도 CPU 코어가 모자란다(340%는 코어 약 3.4개를 꽉 쓴 상태).
- 쿼리가 오래 걸리면 커넥션을 오래 잡는다 → HikariCP 커넥션 풀(앱 쪽 DB 연결 묶음)이 비고, 뒤 요청은 커넥션을 기다리다 시한을 넘긴다 → 실패율 40%.
- 그래서 한계가 초당 40건에서 10건으로 내려갔다. 코드와 Gateway는 그대로이고 **DB가 고른 실행 방법만** 달라졌다.

11/11에는 측정 회차가 통계를 모은 뒤에 있었기 때문에(또는 그 사이 자동 ANALYZE가 돌았기 때문에) B 계획이 나왔다고 보는 것이 자연스럽다. 이것도 확인 질문 4로 확인한다.

## 4. "DB가 원래 가끔 스스로 한다"는데 왜 이번엔 안 했나

자동 통계 수집은 **autovacuum**(PostgreSQL 백그라운드 정리 작업)이 한다. 테이블의 변경 행 수가 아래 기준을 넘으면 그 테이블을 ANALYZE한다.

```
변경 행 수 > autovacuum_analyze_threshold(기본 50) + autovacuum_analyze_scale_factor(기본 0.1) × 테이블 행 수
```

기본값이면 **테이블의 10%가 바뀌어야** 돈다. 예를 들어 `schedule_seats`에 이미 15,000행짜리 회차가 10개 있다면(15만 행) 기준은 약 15,050행이다. 새 회차 15,000행을 넣어도 기준에 **딱 못 미친다**. 회차가 많을수록 새 회차 하나는 비율이 더 작아져 자동 ANALYZE가 더 늦게 돈다.

또 기준을 넘더라도 autovacuum은 주기(`autovacuum_naptime`, 기본 1분)마다 살피므로, 시드 직후 바로 부하를 넣으면 그 틈에 걸린다.

(위 15만 행은 예시다. 실제 `schedule_seats` 행 수는 확인 못 함. 확인 질문 3.)

## 5. 이게 측정만의 문제가 아닌 이유

티켓팅 서비스의 실제 흐름이 바로 이 패턴이다.

1. 운영자가 새 회차를 등록한다 → 좌석 수만 행이 한꺼번에 생긴다.
2. 얼마 뒤 오픈 시각에 수만 명이 같은 회차 좌석을 동시에 조회한다.

1과 2 사이에 통계가 갱신되지 않으면, 오픈 순간 첫 요청부터 A 계획으로 돈다. 측정에서 본 "초당 10건 한계"가 오픈 순간에 그대로 나온다. 그래서 `ANALYZE` 한 번은 측정을 다시 하기 위한 조치이고, 재발 방지는 설계 결정으로 따로 다뤄야 한다.

## 6. 재발 방지 선택지

| 선택지 | 방법 | 장점 | 감수할 점 |
|---|---|---|---|
| ① 좌석 생성 직후 `ANALYZE schedule_seats` | 회차 좌석을 만드는 트랜잭션이 커밋된 뒤 같은 흐름에서 실행. 측정 시드 스크립트에도 넣음 | 원인을 정확히 겨냥. 회차 등록은 드물어 부담이 작다 | 앱이 DB 관리 명령을 부른다(권한 필요). 표본 추출이라 수십~수백 ms가 걸린다 |
| ② 테이블별 자동 ANALYZE 기준 낮추기 | `ALTER TABLE schedule_seats SET (autovacuum_analyze_scale_factor = 0, autovacuum_analyze_threshold = 5000);` 같은 설정을 마이그레이션에 둠 | 코드 변경 없음. 다른 대량 변경에도 효과 | 여전히 naptime 동안 틈이 있다. 오픈 직전 생성이면 못 막을 수 있다 |
| ③ 계획 고정·힌트 | `enable_nestloop` 끄기, pg_hint_plan 등 | 확실히 막는다 | 다른 쿼리까지 영향. 통계를 고치는 대신 증상만 누른다. 권하지 않음 |
| ④ 좌석 조회 결과 캐시 | 회차 좌석 배치(열·번호)는 바뀌지 않으므로 앱·Redis에 캐시 | DB 쿼리 자체가 줄어 근본적으로 가볍다 | 별도 설계 범위(상태 반영 지연). 통계 문제와는 별개로 판단할 일 |

추천: **① + ②**. ①이 "생성 직후 바로 트래픽"을 직접 막고, ②는 ①을 빠뜨린 경로(수동 SQL 시드, 일괄 수정)의 안전망이다. ③은 쓰지 않는다. ④는 좌석 조회 개선 논의에서 따로 다룬다. 어느 쪽이든 되돌리기 어려운 운영 규칙이므로, 정하면 `docs/decisions/`에 ADR로 남길 대상이다.

## 7. 확인 질문 (배포 DB에서 보면 위 설명이 맞는지 확정된다)

1. 문제 계획의 증거: 회차 104로 `EXPLAIN (ANALYZE, BUFFERS)`를 실행했을 때 `schedule_seats` 노드가 `rows=1`(추정) 대 `actual rows=15000`이고, 그 위가 `Nested Loop`인가. `ANALYZE` 뒤에는 `Hash Join`으로 바뀌었나.
2. 1행이 나온 경로: `SELECT most_common_vals, most_common_freqs, n_distinct FROM pg_stats WHERE tablename='schedule_seats' AND attname='schedule_id';`에서 104가 빠져 있고 비율 합이 1에 가까운가.
3. 자동 ANALYZE가 안 돈 이유: `SELECT n_live_tup, n_mod_since_analyze, last_analyze, last_autoanalyze FROM pg_stat_user_tables WHERE relname='schedule_seats';`에서 `n_mod_since_analyze`가 기준(50 + 0.1 × 행 수)보다 작은가, `last_autoanalyze`가 회차 104 생성 시각보다 이전인가.
4. 11/11과의 차이: 11/11 측정 회차는 마지막 ANALYZE보다 먼저 만들어졌나.

## 앞선 노트와의 관계
`2026-11-13-seat-query-cpu-5x-comparison.md`는 "CPU 5배 차이가 Gateway 때문인지, 같은 조건 비교인지부터 보자"였다. 이 노트는 그 뒤 찾은 원인(DB 통계와 조인 계획)을 설명한다. Gateway 변경은 원인이 아니었다.

## 이 저장소에서 확인할 곳
- 조인 쿼리: `reservation-service/src/main/java/com/iseonjwa/reservation/concert/infrastructure/persistence/SpringDataScheduleSeatRepository.java`(`schedule_seats` ⨝ `venue_seats`)
- 인덱스: `reservation-service/src/main/resources/db/migration/V20261029_1__concert_tables.sql`(`idx_schedule_seats_schedule_id_section`)
- PostgreSQL 문서: "Row Estimation Examples"(planner-stats-details), "The Autovacuum Daemon"(routine-vacuuming)
