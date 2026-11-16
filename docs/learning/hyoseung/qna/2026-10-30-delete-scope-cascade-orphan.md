---
title: 부모를 지우면 자식은 어디까지 지우나, cascade와 orphanRemoval은 soft delete에 쓰나
date: 2026-10-30
updated: 2026-10-30
asker: hyos1
level: 중급
tags: [jpa, cascade, orphan-removal, soft-delete, aggregate]
---

# 부모를 지우면 자식은 어디까지 지우나, cascade와 orphanRemoval은 soft delete에 쓰나

## 질문
게시물을 지우면 댓글·좋아요도 지우듯, 공연을 지우면 회차도 지워야 하지 않나. 자식만 지울 때(댓글 하나 삭제)도 있다. 무엇이 어디에 속하고 삭제가 어디까지 번지나. JPA의 cascade와 orphanRemoval 중 무엇으로 하나.

## 답
### 기준: "속한다(소유)"와 "가리킨다(참조)"를 나눈다
FK가 있다고 모두 부모·자식이 아니다. 자식이 부모 없이 의미가 없으면 **소유**, 부모 없이도 따로 의미가 있으면 **참조**다.
- 게시물 → 댓글·좋아요: 소유. 게시물이 없으면 댓글도 의미가 없으니 같이 지운다.
- 댓글 → 작성자(회원): 참조. 댓글을 지운다고 회원을 지우지 않고, 회원을 지울 때 댓글을 어떻게 할지는 따로 정한다.

이 프로젝트 공연·좌석 영역에 적용하면:

| 관계 | 종류 | 부모를 지울 때 |
|---|---|---|
| 공연 → 회차 | 소유 | 회차도 함께 soft delete |
| 회차 → 회차 좌석 | 소유 | 좌석은 soft delete를 쓰지 않는다(SA 12-1). 회차가 숨으면 좌석은 회차를 통해서만 보이므로 따로 지우지 않는다 |
| 공연장 → 공연장 좌석 | 소유 | 함께 soft delete |
| 회차 → 공연장 | 참조 | 공연장을 쓰는 회차가 있으면 공연장 삭제를 막는다 |
| 회차 좌석 → 공연장 좌석 | 참조 | 회차 좌석이 쓰고 있으면 막는다 |
| 예매 → 회차·회차 좌석 | 참조 | 예매가 있으면 공연·회차 삭제를 막는다 |

자식만 지우는 경우(회차 하나만 내림)도 같은 규칙을 따른다. 회차에 예매가 없으면 지우고, 있으면 막는다.

### 판매가 시작된 뒤는 "삭제"가 아니라 "취소"다
soft delete는 운영자가 잘못 올렸거나 판매 전에 내리는 데이터를 숨기는 용도다(SA 12-1 "운영자가 내리는 데이터"). 표가 팔린 공연을 없애는 일은 취소 상태 전이와 환불이 필요한 다른 업무다. 예매가 참조하면 삭제를 막는 이유이기도 하다.

### cascade와 orphanRemoval
둘 다 부모 엔티티의 자식 컬렉션(`@OneToMany`)에 붙인다.
- **cascade**: 부모에게 한 EntityManager 작업(persist, remove 등)을 자식에게도 전파한다. `CascadeType.REMOVE`면 부모를 `remove`할 때 자식도 `remove`한다.
- **orphanRemoval**: 부모의 컬렉션에서 자식을 빼면(관계가 끊기면) 그 자식을 `DELETE`한다. "게시물에서 댓글 하나 지우기" 같은 자식 단독 삭제에 해당한다.

둘 다 실제 `DELETE` SQL을 내는 기능이다. soft delete는 `UPDATE ... SET deleted_at`이라 그대로는 맞지 않는다. `@SQLDelete`로 DELETE를 UPDATE로 바꿔 끼우는 방법이 있지만 이 프로젝트에서는 쓰지 않는 쪽을 추천한다.
- 지금 엔티티는 `@ManyToOne`만 있고 `@OneToMany` 컬렉션이 없다(10/29에 단방향으로 정함). cascade를 쓰려면 양방향을 추가해야 한다.
- cascade는 자식을 전부 메모리에 올려 한 건씩 처리한다. 회차 좌석처럼 수만 건이면 느리다.
- `deleted_by`(누가 지웠나)를 채우기 어렵고, "예매가 있으면 막는다" 같은 검사를 넣을 자리가 없다.

대신 삭제 서비스에서 한 트랜잭션으로 명시적으로 처리한다.
1. 예매가 있는지 확인하고 있으면 거절한다.
2. `UPDATE schedules SET deleted_at = ?, deleted_by = ? WHERE concert_id = ? AND deleted_at IS NULL`처럼 자식을 한 번에 soft delete한다.
3. 공연 엔티티의 삭제 메서드로 부모를 soft delete한다.

삭제 기능은 MVP 범위 밖이라 구현할 때 이 순서를 따른다.

### 이해 확인에서 바로잡은 것 (후속 질문)
- **"cascade를 켜야 자식 수정이 DB에 반영된다"는 아니다.** 이미 영속 상태인 자식의 필드 수정은 변경 감지(dirty checking)가 cascade와 상관없이 반영한다. cascade가 전파하는 것은 `persist`·`remove`·`merge` 같은 EntityManager 작업이다. 컬렉션에 새 자식을 넣고 부모를 저장할 때 자식도 INSERT되는 것(`PERSIST`), 부모를 지울 때 자식도 지워지는 것(`REMOVE`)이 cascade다.
- **컬렉션에서 자식을 빼는 것은 cascade가 아니라 orphanRemoval 몫이다.** `CascadeType.ALL`이어도 리스트에서 빼기만 하면 DELETE가 나가지 않는다.
- **양방향이 필수는 아니다.** 부모 쪽에 자식 컬렉션(`@OneToMany`)만 있으면 되고, 단방향 `@OneToMany`도 가능하다. 다만 단방향 `@OneToMany`는 FK가 자식 테이블에 있어 INSERT 뒤 UPDATE가 더 나가는 단점이 있어 보통 양방향으로 쓴다. 우리 엔티티는 부모에 컬렉션이 없어 둘 다 쓸 수 없다.
- 회차 삭제 가능 여부는 `SELECT EXISTS (SELECT 1 FROM reservations WHERE schedule_id = ?)`처럼 존재만 본다. 판매 기록은 5년 보관 대상이라 만료·취소된 예매도 남아 있으면 막는다.

## 핵심 정리
- 소유(같이 죽는다)와 참조(막는다)를 먼저 나눈다. 판매 뒤에는 삭제가 아니라 취소다.
- cascade는 작업 전파, orphanRemoval은 관계가 끊긴 자식 삭제. 둘 다 실제 DELETE용이라 soft delete에는 명시적 일괄 UPDATE를 쓴다.

## 참고
- SA MASTER 12-1 (soft delete 범위)
- [2026-10-30-soft-delete-fetch-join.md](2026-10-30-soft-delete-fetch-join.md)
