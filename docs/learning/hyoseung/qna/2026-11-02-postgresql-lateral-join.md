---
title: LEFT JOIN LATERAL은 무엇이고, 공연마다 다음 회차 하나를 붙일 때 왜 쓰나
date: 2026-11-02
asker: hyos1
level: 기초
tags: [postgresql, sql, lateral, join, top-n-per-group]
---

# LEFT JOIN LATERAL은 무엇이고, 공연마다 다음 회차 하나를 붙일 때 왜 쓰나

## 질문
공연 목록 조회를 쿼리 하나로 바꾸자며 나온 LATERAL 방식은 무엇인가?

## 답

### 1. 풀려는 문제: 그룹마다 하나씩 뽑기
공연 20개 각각에 대해 "아직 시작하지 않은 회차 중 가장 빠른 것 하나"를 붙이고 싶다. 이런 문제를 "그룹별 상위 N개(top-N per group)"라고 부른다.

일반 JOIN으로는 안 된다.

```sql
-- 공연 하나에 남은 회차가 3개면 3줄이 나온다. 하나만 고를 방법이 없다
SELECT c.id, s.starts_at
FROM concerts c LEFT JOIN schedules s ON s.concert_id = c.id AND s.starts_at > now();
```

서브쿼리에 `LIMIT 1`을 걸고 싶지만, 보통 FROM 안의 서브쿼리는 바깥 테이블(`c`)을 참조할 수 없다.

```sql
-- 오류: 서브쿼리 안에서 c.id를 모른다
SELECT ... FROM concerts c
LEFT JOIN (SELECT ... FROM schedules WHERE concert_id = c.id ORDER BY starts_at LIMIT 1) s ON true;
```

### 2. LATERAL: 바깥 줄을 보고 서브쿼리를 돈다
`LATERAL`을 붙이면 서브쿼리가 바깥 테이블의 현재 줄을 참조할 수 있다. 자바의 for-each와 같다.

```java
for (Concert c : concerts) {                    // FROM concerts c
    Schedule s = 회차들 중 c의 것, starts_at > now, 시작 순 첫 번째;  // LATERAL (... LIMIT 1)
    결과.add(c, s);                               // s가 없어도 c는 남긴다 = LEFT ... ON true
}
```

```sql
SELECT c.id, c.title, s.starts_at, s.open_at
FROM concerts c
LEFT JOIN LATERAL (
    SELECT starts_at, open_at
    FROM schedules
    WHERE concert_id = c.id            -- 바깥 줄(c)을 참조한다
      AND starts_at > :now
      AND deleted_at IS NULL
    ORDER BY starts_at
    LIMIT 1                            -- 공연마다 딱 하나
) s ON true
WHERE c.deleted_at IS NULL
ORDER BY c.id DESC
LIMIT :size OFFSET :offset;
```

- `LEFT JOIN ... ON true`: 서브쿼리 결과가 없어도(남은 회차 없음) 공연 줄은 남기고 회차 칸은 null로 채운다. 연결 조건은 서브쿼리 안 `WHERE concert_id = c.id`가 이미 했으므로 ON에는 조건이 필요 없다.
- 결과 예시

```
id  title             starts_at         open_at
10  이선좌 콘서트 2026  2026-11-20 19:00  2026-11-01 20:00
9   가을 앙코르          null              null
8   겨울 투어           2026-12-05 18:00  2026-11-15 20:00
```

공연 순서가 그대로이고, 공연마다 한 줄이라 Map으로 다시 짝지을 필요가 없다.

### 3. 성능
- 바깥 `LIMIT 20`이 먼저 공연 20줄로 줄인 뒤 LATERAL을 돌린다고 보면 된다. 서브쿼리는 공연 수만큼, 즉 20번 돈다.
- 서브쿼리는 `schedules(concert_id)` 인덱스로 그 공연의 회차만 찾고 `LIMIT 1`에서 멈춘다. 공연 하나의 회차는 수십 개 이하라 가볍다.
- 기존 방식은 20개 공연의 남은 회차를 전부 자바로 가져왔다. LATERAL은 DB 안에서 하나만 골라 20줄만 보낸다.

### 4. 다른 방법과 비교
| 방법 | 모양 | 특징 |
|---|---|---|
| LATERAL + LIMIT 1 | 위 쿼리 | 의도("공연마다 첫 회차")가 그대로 읽힌다. N을 바꾸려면 LIMIT만 바꾼다 |
| `ROW_NUMBER() OVER (PARTITION BY concert_id ORDER BY starts_at)` | 회차에 공연별 순번을 매기고 1번만 남긴다 | 표준 SQL이다. 서브쿼리를 한 겹 더 감싸야 한다 |
| `DISTINCT ON (concert_id)` | 공연별 첫 줄만 남긴다 | PostgreSQL 전용이고 짧다. 정렬 규칙을 알아야 읽힌다 |

### 5. 주의할 점
- LATERAL은 SQL 표준에 있고 PostgreSQL·MySQL 8.0.14+·Oracle 12c+가 지원한다. 우리는 PostgreSQL만 쓴다.
- JPA에서는 native query로 쓴다. native query에는 엔티티의 `@SQLRestriction`(지운 행 숨기기)이 붙지 않으므로 `deleted_at IS NULL`을 직접 적는다.
- 결과가 엔티티 모양이 아니므로 결과 한 줄을 받는 객체(인터페이스 프로젝션 등)를 둔다.
