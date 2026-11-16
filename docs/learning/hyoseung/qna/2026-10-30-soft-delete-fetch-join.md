---
title: soft delete 필터(@SQLRestriction)와 연관 조회, fetch join으로 지운 데이터를 거르면 되는가
date: 2026-10-30
updated: 2026-10-30
asker: hyos1
level: 중급
tags: [jpa, hibernate, soft-delete, fetch-join, lazy-loading]
---

# soft delete 필터와 연관 조회, fetch join으로 지운 데이터를 거르면 되는가

## 질문
공연(`Concert`)에 `@SQLRestriction("deleted_at IS NULL")`을 걸면 회차(`Schedule`)에서 지운 공연을 따라갈 때 어떻게 되나. fetch join은 내부 조인이라 지운 공연의 회차가 결과에서 빠지는데, 그러면 조회할 때 그냥 fetch join을 쓰면 되나.

## 답
### 필터를 건 엔티티를 직접 읽을 때
`select c from Concert c`, `em.find(Concert.class, id)` 모두 SQL에 `deleted_at IS NULL`이 붙어 지운 공연이 나오지 않는다(find는 null).

### 회차에서 지운 공연을 따라갈 때 (지연 로딩)
`schedule.getConcert()`는 진짜 객체가 아니라 프록시다. 프록시는 회차 행의 FK 값(`concert_id`)만 들고 있다.
- `getConcert().getId()`: DB에 가지 않고 FK 값으로 답한다. 그래서 id는 나온다.
- `getConcert().getTitle()`: 이때 공연을 조회하는데 필터 때문에 행이 없어 `EntityNotFoundException`이 난다.

### fetch join
JPQL `join fetch`는 기본이 내부 조인이다. 공연 행이 필터로 빠지면 짝이 없는 회차 행도 결과에서 빠진다. 공연 목록 전체를 조회해도 지운 공연에 속한 회차는 전부 안 나온다.

`SoftDeleteFilterTest`(reservation-service)가 위 세 동작을 확인한다.

### 그러면 fetch join으로 거르면 되나
동작은 하지만 지운 데이터를 거르는 수단으로 기대지 않는다.
- **우연에 기댄 동작이다.** 누가 N+1이 문제없다고 보고 join을 빼거나 `left join fetch`로 바꾸면 지운 공연의 회차가 다시 나오고, 그 회차에서 공연 이름을 읽는 순간 예외가 난다. 코드를 읽는 사람도 "이 조인이 삭제 필터 역할을 한다"를 알기 어렵다.
- **기준은 회차 자신이 들고 있어야 한다.** 공연을 지울 때 그 공연의 회차도 함께 soft delete하면 회차 조회에 `Schedule`의 필터가 바로 걸린다. 조인 여부와 상관없이 같은 결과가 나온다.
- **fetch join은 성능 목적으로 쓴다.** 공연 정보가 필요한 조회에서 N+1을 막으려고 쓰고, 필요 없으면 조인하지 않는다.

정리하면 "삭제 규칙(공연 삭제 시 회차도 삭제, 좌석·예매가 참조하면 삭제 금지)으로 데이터를 일관되게 두고, fetch join은 성능을 위해서만 쓴다"가 기준이다. 삭제 기능은 MVP 범위 밖이라 그때 구현한다.

## 핵심 정리
- 지연 로딩 프록시는 id만 안다. 내용을 읽을 때 조회하고, 필터에 걸리면 `EntityNotFoundException`.
- inner fetch join은 지운 부모의 자식도 빼지만, 그 효과에 기대지 말고 자식에도 삭제 상태를 남긴다.

## 참고
- SA MASTER 12-1 (soft delete 기본 필터)
- `reservation-service/src/test/java/com/iseonjwa/reservation/concert/domain/entity/SoftDeleteFilterTest.java`
